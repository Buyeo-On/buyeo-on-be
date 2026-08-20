package com.buyeoon.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.point.application.PointSettlementService;
import com.buyeoon.point.entity.SettlementChoice;
import com.jayway.jsonpath.JsonPath;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PointSettlementIntegrationTests {

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

	@Autowired
	private PointSettlementService pointSettlementService;

	private AuthenticatedMember member;

	@BeforeEach
	void setUpMember() {
		member = insertAuthenticatedMember();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM point_settlements");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 양수 정산 대상을 LEAVE_TO_BUYEO로 정산하면 해당 여행 적립분만 차감하고 다른 여행 이월분은 유지한다. */
	@Test
	@DisplayName("LEAVE_TO_BUYEO는 해당 여행 적립분만 차감하고 다른 여행 이월분은 유지한다")
	void leaveToBuyeoDeductsOnlyThisTripsEarnings() throws Exception {
		UUID otherTrip = insertEndedTrip(member.memberId());
		insertTransaction(otherTrip, "EARN", 500, "다른 여행 적립");
		insertCarryOverSettlement(otherTrip, 500, Instant.now().plus(5, ChronoUnit.DAYS));

		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 300, "미션 보상");

		performSettle(member, tripId, "leave-key-01", "LEAVE_TO_BUYEO").andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.tripId").value(tripId.toString()))
				.andExpect(jsonPath("$.data.choice").value("LEAVE_TO_BUYEO"))
				.andExpect(jsonPath("$.data.settledPoints").value(300))
				.andExpect(jsonPath("$.data.remainingBalance").value(500))
				.andExpect(jsonPath("$.data.expiresAt").isEmpty()).andExpect(jsonPath("$.data.settledAt").isString())
				.andExpect(jsonPath("$.data.newlyAwardedBadges").isArray())
				.andExpect(jsonPath("$.data.newlyAwardedBadges.length()").value(0));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(amount),0) FROM point_transactions WHERE member_id = ?", Long.class,
				member.memberId())).isEqualTo(500L);
		assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM trips WHERE id = ?", String.class, tripId))
				.isEqualTo("SETTLED");
		assertThat(jdbcTemplate.queryForObject("SELECT settled_at IS NOT NULL FROM trips WHERE id = ?", Boolean.class,
				tripId)).isTrue();
	}

	/**
	 * 양수 정산 대상을 CARRY_OVER로 정산하면 잔액을 유지하고 settledAt부터 240시간 뒤를 expiresAt으로 기록한다.
	 */
	@Test
	@DisplayName("CARRY_OVER는 잔액을 유지하고 settledAt부터 240시간 뒤를 expiresAt으로 기록한다")
	void carryOverKeepsBalanceAndRecordsExpiry() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 200, "미션 보상");

		MvcResult result = performSettle(member, tripId, "carry-key-01", "CARRY_OVER").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.choice").value("CARRY_OVER"))
				.andExpect(jsonPath("$.data.settledPoints").value(200))
				.andExpect(jsonPath("$.data.remainingBalance").value(200))
				.andExpect(jsonPath("$.data.expiresAt").isString()).andReturn();

		String settledAtText = JsonPath.read(result.getResponse().getContentAsString(), "$.data.settledAt");
		String expiresAtText = JsonPath.read(result.getResponse().getContentAsString(), "$.data.expiresAt");
		assertThat(Instant.parse(expiresAtText)).isEqualTo(Instant.parse(settledAtText).plus(240, ChronoUnit.HOURS));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(amount),0) FROM point_transactions WHERE member_id = ?", Long.class,
				member.memberId())).isEqualTo(200L);
	}

	/** 정산 대상이 0이면 선택 없이 NO_POINTS로 정산하고 포인트 내역을 만들지 않는다. */
	@Test
	@DisplayName("정산 대상이 0이면 선택 없이 NO_POINTS로 정산한다")
	void settlesWithNoPointsWhenNothingToSettle() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());

		performSettle(member, tripId, "no-points-key-01", null).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.choice").value("NO_POINTS"))
				.andExpect(jsonPath("$.data.settledPoints").value(0))
				.andExpect(jsonPath("$.data.remainingBalance").value(0))
				.andExpect(jsonPath("$.data.expiresAt").isEmpty());

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM point_transactions WHERE member_id = ?",
				Integer.class, member.memberId())).isEqualTo(0);
		assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM trips WHERE id = ?", String.class, tripId))
				.isEqualTo("SETTLED");
	}

	/** 정산 대상이 양수인데 선택이 없으면 400을 반환한다. */
	@Test
	@DisplayName("정산 대상이 양수인데 선택이 없으면 400을 반환한다")
	void missingChoiceForPositiveSettleableReturnsBadRequest() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 100, "미션 보상");

		performSettle(member, tripId, "bad-choice-key-01", null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** 정산 대상이 0인데 선택을 보내면 400을 반환한다. */
	@Test
	@DisplayName("정산 대상이 0인데 선택을 보내면 400을 반환한다")
	void choiceForZeroSettleableReturnsBadRequest() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());

		performSettle(member, tripId, "bad-choice-key-02", "CARRY_OVER").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** Idempotency-Key가 없으면 400을 반환한다. */
	@Test
	@DisplayName("Idempotency-Key가 없으면 400을 반환한다")
	void missingIdempotencyKeyReturnsBadRequest() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());

		performSettleWithoutKey(member, tripId, "CARRY_OVER").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** 존재하지 않는 여행은 404를 반환한다. */
	@Test
	@DisplayName("존재하지 않는 여행은 404를 반환한다")
	void nonExistentTripReturnsNotFound() throws Exception {
		performSettle(member, UUID.randomUUID(), "missing-trip-key", "CARRY_OVER").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 다른 회원이 소유한 여행은 404를 반환한다. */
	@Test
	@DisplayName("다른 회원 소유 여행은 404를 반환한다")
	void otherMembersTripReturnsNotFound() throws Exception {
		AuthenticatedMember other = insertAuthenticatedMember();
		UUID tripId = insertEndedTrip(other.memberId());

		performSettle(member, tripId, "other-owner-key", "CARRY_OVER").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 진행 중인 여행은 409를 반환한다. */
	@Test
	@DisplayName("진행 중인 여행은 409를 반환한다")
	void inProgressTripReturnsConflict() throws Exception {
		UUID tripId = insertInProgressTrip(member.memberId());

		performSettle(member, tripId, "in-progress-key", "CARRY_OVER").andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** 인증하지 않으면 401을 받는다. */
	@Test
	@DisplayName("인증하지 않으면 401을 받는다")
	void unauthenticatedRequestReturnsUnauthorized() throws Exception {
		MockHttpServletRequestBuilder request = put("/trips/{tripId}/settlement", UUID.randomUUID())
				.header("Idempotency-Key", "unauth-settle-key").contentType(MediaType.APPLICATION_JSON).content("{}");
		mockMvc.perform(request).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 같은 키·같은 선택 replay는 최초 semantic result를 반환하고 부작용을 반복하지 않는다. */
	@Test
	@DisplayName("같은 키의 재요청은 최초 응답을 재사용하고 부작용을 반복하지 않는다")
	void sameIdempotentRequestReturnsFirstResponseWithoutSideEffects() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 150, "미션 보상");
		String key = "replay-key-01";

		String first = performSettle(member, tripId, key, "LEAVE_TO_BUYEO").andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String retried = performSettle(member, tripId, key, "LEAVE_TO_BUYEO").andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

		assertThat(retried).isEqualTo(first);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'LEAVE_TO_BUYEO'",
				Integer.class, member.memberId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM point_settlements WHERE trip_id = ?",
				Integer.class, tripId)).isEqualTo(1);
	}

	/** 같은 키를 다른 선택으로 재사용하면 409를 반환한다. */
	@Test
	@DisplayName("같은 키를 다른 선택으로 재사용하면 409를 반환한다")
	void reusingKeyWithDifferentChoiceReturnsConflict() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 150, "미션 보상");
		String key = "reuse-key-01";

		performSettle(member, tripId, key, "LEAVE_TO_BUYEO").andExpect(status().isOk());
		UUID anotherTrip = insertEndedTrip(member.memberId());
		insertTransaction(anotherTrip, "EARN", 50, "미션 보상");

		performSettle(member, anotherTrip, key, "CARRY_OVER").andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	/** 이미 정산된 여행을 다른 키로 재정산하면 409를 반환한다. */
	@Test
	@DisplayName("이미 정산된 여행을 다른 키로 재정산하면 409를 반환한다")
	void resettlingSettledTripWithDifferentKeyReturnsConflict() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 100, "미션 보상");
		performSettle(member, tripId, "resettle-key-a", "LEAVE_TO_BUYEO").andExpect(status().isOk());

		performSettle(member, tripId, "resettle-key-b", "LEAVE_TO_BUYEO").andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** 같은 여행의 동시 정산 요청은 하나만 확정한다. */
	@Test
	@DisplayName("동시 정산 요청은 하나만 성공한다")
	void concurrentSettlementRequestsSucceedOnce() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 100, "미션 보상");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor
					.submit(() -> concurrentSettle(member, tripId, "concurrent-settle-a", ready, start));
			Future<MvcResult> second = executor
					.submit(() -> concurrentSettle(member, tripId, "concurrent-settle-b", ready, start));
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
				.isEqualTo("SETTLED");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM point_settlements WHERE trip_id = ?",
				Integer.class, tripId)).isEqualTo(1);
	}

	/** 전체 잔액이 정산 대상보다 작으면 다른 여행 포인트를 차감하지 않고 정산 전체를 실패시킨다. */
	@Test
	@DisplayName("전체 잔액이 정산 대상보다 작으면 정산 전체를 실패시키고 상태를 유지한다")
	void insufficientBalanceFailsSettlementEntirely() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 300, "미션 보상");
		insertTransaction(tripId, "ADJUST", -250, "운영 조정");

		assertThatThrownBy(() -> pointSettlementService.settle(member.memberId(), tripId, "insufficient-balance-key",
				SettlementChoice.LEAVE_TO_BUYEO)).isInstanceOf(IllegalStateException.class);

		assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM trips WHERE id = ?", String.class, tripId))
				.isEqualTo("ENDED");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM point_settlements WHERE trip_id = ?",
				Integer.class, tripId)).isEqualTo(0);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests WHERE member_id = ?",
				Integer.class, member.memberId())).isEqualTo(0);
	}

	private MvcResult concurrentSettle(AuthenticatedMember member, UUID tripId, String key, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return performSettle(member, tripId, key, "LEAVE_TO_BUYEO").andReturn();
	}

	private ResultActions performSettle(AuthenticatedMember member, UUID tripId, String idempotencyKey, String choice)
			throws Exception {
		String body = choice == null ? "{}" : "{\"choice\":\"" + choice + "\"}";
		MockHttpServletRequestBuilder request = put("/trips/{tripId}/settlement", tripId)
				.header("Authorization", "Bearer " + member.accessToken()).header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content(body);
		return mockMvc.perform(request);
	}

	private ResultActions performSettleWithoutKey(AuthenticatedMember member, UUID tripId, String choice)
			throws Exception {
		String body = choice == null ? "{}" : "{\"choice\":\"" + choice + "\"}";
		MockHttpServletRequestBuilder request = put("/trips/{tripId}/settlement", tripId)
				.header("Authorization", "Bearer " + member.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content(body);
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

	private void insertTransaction(UUID tripId, String type, long amount, String description) {
		jdbcTemplate.update(
				"INSERT INTO point_transactions (id, member_id, trip_id, type, amount, description, occurred_at) "
						+ "VALUES (?, ?, ?, ?::point_transaction_type, ?, ?, ?)",
				UUID.randomUUID(), member.memberId(), tripId, type, amount, description, Timestamp.from(Instant.now()));
	}

	private UUID insertInProgressTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status) VALUES (?, ?, 'IN_PROGRESS')", tripId, memberId);
		return tripId;
	}

	private UUID insertEndedTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status, ended_at) VALUES (?, ?, 'ENDED', now())", tripId,
				memberId);
		return tripId;
	}

	private void insertCarryOverSettlement(UUID tripId, long settledPoints, Instant expiresAt) {
		Instant settledAt = expiresAt.minus(240, ChronoUnit.HOURS);
		jdbcTemplate.update(
				"INSERT INTO point_settlements (id, trip_id, choice, settled_points, expires_at, settled_at) "
						+ "VALUES (?, ?, 'CARRY_OVER', ?, ?, ?)",
				UUID.randomUUID(), tripId, settledPoints, Timestamp.from(expiresAt), Timestamp.from(settledAt));
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
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
