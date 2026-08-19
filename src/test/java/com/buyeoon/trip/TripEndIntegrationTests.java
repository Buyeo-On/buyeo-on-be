package com.buyeoon.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TripEndIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AccessTokenService accessTokenService;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 진행 중인 여행을 종료하면 상태가 ENDED로 바뀌고 endedAt이 기록된다. */
	@Test
	@DisplayName("진행 중인 여행을 종료하면 ENDED로 전이하고 endedAt을 기록한다")
	void endsInProgressTrip() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId());

		performEnd(member, tripId, "end-trip-key-01").andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.tripId").value(tripId.toString()))
				.andExpect(jsonPath("$.data.status").value("ENDED")).andExpect(jsonPath("$.data.endedAt").isString())
				.andExpect(jsonPath("$.data.settledAt").isEmpty());

		assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM trips WHERE id = ?", String.class, tripId))
				.isEqualTo("ENDED");
		assertThat(jdbcTemplate.queryForObject("SELECT ended_at FROM trips WHERE id = ?", Timestamp.class, tripId))
				.isNotNull();
	}

	/** 존재하지 않는 tripId로 종료를 요청하면 404를 반환한다. */
	@Test
	@DisplayName("존재하지 않는 tripId는 404를 반환한다")
	void nonExistentTripReturnsNotFound() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		performEnd(member, UUID.randomUUID(), "end-trip-key-03").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 다른 회원 소유의 tripId로 종료를 요청하면 404를 반환한다. */
	@Test
	@DisplayName("타 회원 소유 tripId는 404를 반환한다")
	void otherMembersTripReturnsNotFound() throws Exception {
		AuthenticatedMember owner = insertAuthenticatedMember();
		AuthenticatedMember requester = insertAuthenticatedMember();
		UUID tripId = insertTrip(owner.memberId());

		performEnd(requester, tripId, "end-trip-key-04").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 인증되지 않은 요청은 401을 반환한다. */
	@Test
	@DisplayName("인증되지 않은 요청은 401을 반환한다")
	void unauthenticatedRequestReturnsUnauthorized() throws Exception {
		mockMvc.perform(post("/trips/" + UUID.randomUUID() + "/end").header("Idempotency-Key", "unauth-end-key"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** Idempotency-Key 없이 요청하면 400을 반환한다. */
	@Test
	@DisplayName("Idempotency-Key가 없으면 400을 반환한다")
	void missingIdempotencyKeyReturnsBadRequest() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId());

		performEnd(member, tripId, null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** 같은 여행을 같은 멱등성 키로 다시 요청하면 최초 성공 응답을 그대로 반환한다. */
	@Test
	@DisplayName("같은 멱등성 키의 재요청은 최초 응답을 재사용한다")
	void sameIdempotentRequestReturnsFirstResponse() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId());
		String key = "end-trip-key-05";

		String first = performEnd(member, tripId, key).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		String retried = performEnd(member, tripId, key).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();

		assertThat(retried).isEqualTo(first);
	}

	/** 이미 종료된 여행을 다른 멱등성 키로 다시 종료하면 409를 반환한다. */
	@Test
	@DisplayName("이미 종료된 여행을 다른 키로 재종료하면 409를 반환한다")
	void endingAlreadyEndedTripWithDifferentKeyConflicts() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId());
		performEnd(member, tripId, "end-trip-key-06a").andExpect(status().isOk());

		performEnd(member, tripId, "end-trip-key-06b").andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** 같은 여행에 대한 동시 종료 요청은 하나만 성공한다. */
	@Test
	@DisplayName("동시 종료 요청은 하나만 성공한다")
	void concurrentEndRequestsSucceedOnce() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId());
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor
					.submit(() -> concurrentEnd(member, tripId, "concurrent-end-a", ready, start));
			Future<MvcResult> second = executor
					.submit(() -> concurrentEnd(member, tripId, "concurrent-end-b", ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
			int[] statuses = {firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()};
			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM trips WHERE id = ?", String.class, tripId))
				.isEqualTo("ENDED");
	}

	private MvcResult concurrentEnd(AuthenticatedMember member, UUID tripId, String key, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return performEnd(member, tripId, key).andReturn();
	}

	private ResultActions performEnd(AuthenticatedMember member, UUID tripId, String key) throws Exception {
		var request = post("/trips/" + tripId + "/end").header("Authorization", "Bearer " + member.accessToken());
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mockMvc.perform(request);
	}

	private AuthenticatedMember insertAuthenticatedMember() {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
		return new AuthenticatedMember(memberId, accessTokenService.issue(memberId, sessionId));
	}

	private UUID insertTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status) VALUES (?, ?, 'IN_PROGRESS')", tripId, memberId);
		return tripId;
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> APPLICATION_USERNAME);
		registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
