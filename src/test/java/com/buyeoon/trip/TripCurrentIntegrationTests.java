package com.buyeoon.trip;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** UC-05 진행 중인 여행 조회(GET /trips/current)를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TripCurrentIntegrationTests {

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

	@BeforeEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 진행 중인 여행이 있으면 그 여행 정보를 반환한다. */
	@Test
	@DisplayName("진행 중인 여행이 있으면 200과 여행 정보를 반환한다")
	void inProgressTripIsReturned() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS");

		performCurrent(member).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.tripId").value(tripId.toString()))
				.andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.data.startedAt").isString()).andExpect(jsonPath("$.data.endedAt").isEmpty());
	}

	/** 진행 중인 여행이 없으면 404를 반환한다. */
	@Test
	@DisplayName("진행 중인 여행이 없으면 404를 반환한다")
	void noTripReturnsNotFound() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		performCurrent(member).andExpect(status().isNotFound());
	}

	/** 종료된 여행만 있으면(진행 중 여행이 아니므로) 404를 반환한다. */
	@Test
	@DisplayName("종료된 여행만 있으면 404를 반환한다")
	void endedTripOnlyReturnsNotFound() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertTrip(member.memberId(), "ENDED");

		performCurrent(member).andExpect(status().isNotFound());
	}

	/** 인증되지 않은 요청은 진행 중 여행 조회 계층에 도달하지 않는다. */
	@Test
	@DisplayName("여행 조회에는 유효한 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(get("/trips/current")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private ResultActions performCurrent(AuthenticatedMember member) throws Exception {
		return mockMvc.perform(get("/trips/current").header("Authorization", "Bearer " + member.accessToken()));
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

	/** 지정한 상태의 여행 테스트 데이터를 저장한다. */
	private UUID insertTrip(UUID memberId, String status) {
		UUID tripId = UUID.randomUUID();
		Instant startedAt = Instant.parse("2026-08-12T09:00:00Z");
		Instant endedAt = "ENDED".equals(status) ? startedAt.plus(2, ChronoUnit.HOURS) : null;
		jdbcTemplate.update("""
				INSERT INTO trips (id, member_id, status, started_at, ended_at)
				VALUES (?, ?, ?::trip_status, ?, ?)
				""", tripId, memberId, status, Timestamp.from(startedAt),
				endedAt == null ? null : Timestamp.from(endedAt));
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
		registry.add("location.buyeo-boundary", () -> "classpath:boundaries/buyeo-test.geojson");
	}

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
