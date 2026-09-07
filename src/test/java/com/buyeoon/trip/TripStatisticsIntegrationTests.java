package com.buyeoon.trip;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TripStatisticsIntegrationTests {

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
		jdbcTemplate.update("DELETE FROM point_settlements");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM visit_records");
		jdbcTemplate.update("DELETE FROM missions");
		jdbcTemplate.update("DELETE FROM places");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 존재하지 않는 tripId로 조회하면 404를 반환한다. */
	@Test
	@DisplayName("존재하지 않는 tripId는 404를 반환한다")
	void nonExistentTripReturnsNotFound() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		performGet(member, UUID.randomUUID()).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 다른 회원 소유의 tripId로 조회하면 404를 반환한다. */
	@Test
	@DisplayName("타 회원 소유 tripId는 404를 반환한다")
	void otherMembersTripReturnsNotFound() throws Exception {
		AuthenticatedMember owner = insertAuthenticatedMember();
		AuthenticatedMember requester = insertAuthenticatedMember();
		UUID tripId = insertTrip(owner.memberId(), "IN_PROGRESS", null, null);

		performGet(requester, tripId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 인증되지 않은 요청은 401을 반환한다. */
	@Test
	@DisplayName("인증되지 않은 요청은 401을 반환한다")
	void unauthenticatedRequestReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/trips/" + UUID.randomUUID() + "/statistics")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 진행 중인 여행은 시작 시각부터 요청 처리 시각까지의 분 수를 반환한다. */
	@Test
	@DisplayName("진행 중인 여행은 시작부터 현재까지의 분 수를 반환한다")
	void inProgressTripReturnsElapsedDuration() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(90, ChronoUnit.SECONDS);
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", startedAt, null);

		performGet(member, tripId).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.tripId").value(tripId.toString()))
				.andExpect(jsonPath("$.data.durationMinutes").value(1))
				.andExpect(jsonPath("$.data.visitedPlaceCount").value(0))
				.andExpect(jsonPath("$.data.earnedPoints").value(0)).andExpect(jsonPath("$.data.distanceKm").isEmpty())
				.andExpect(jsonPath("$.data.caloriesKcal").isEmpty());
	}

	/** 종료된 여행은 시작 시각부터 종료 시각까지의 분 수를 반환한다. */
	@Test
	@DisplayName("종료된 여행은 시작부터 종료까지의 분 수를 반환한다")
	void endedTripReturnsFixedDuration() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS);
		Instant endedAt = startedAt.plus(30, ChronoUnit.MINUTES);
		UUID tripId = insertTrip(member.memberId(), "ENDED", startedAt, endedAt);

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.durationMinutes").value(30));
	}

	/** 정산 완료된 여행도 시작부터 종료까지의 분 수를 반환한다. */
	@Test
	@DisplayName("정산 완료된 여행은 시작부터 종료까지의 분 수를 반환한다")
	void settledTripReturnsFixedDuration() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS);
		Instant endedAt = startedAt.plus(45, ChronoUnit.MINUTES);
		UUID tripId = insertTrip(member.memberId(), "SETTLED", startedAt, endedAt);

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.durationMinutes").value(45));
	}

	/** 방문 기록이 없는 여행은 visitedPlaceCount 0을 반환한다. */
	@Test
	@DisplayName("방문 기록이 없으면 visitedPlaceCount는 0이다")
	void noVisitRecordsReturnsZeroCount() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.visitedPlaceCount").value(0));
	}

	/** 같은 여행에서 같은 문화재를 여러 미션으로 방문 확정해도 visitedPlaceCount는 한 번만 계산한다. */
	@Test
	@DisplayName("같은 문화재의 중복 방문 기록은 한 번만 카운트한다")
	void duplicatePlaceVisitCountedOnce() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);
		UUID placeId = insertPlace("문화재 A");
		UUID missionA = insertMission(placeId, "미션 A");
		UUID missionB = insertMission(placeId, "미션 B");
		insertVisitRecord(tripId, missionA, placeId);
		jdbcTemplate.update(
				"INSERT INTO visit_records (id, trip_id, mission_id, place_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
				UUID.randomUUID(), tripId, missionB, placeId);

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.visitedPlaceCount").value(1));
	}

	/** 진행 중인 여행에 EARN 내역이 없으면 earnedPoints는 0이다. */
	@Test
	@DisplayName("EARN이 없으면 earnedPoints는 0이다")
	void inProgressTripWithoutEarnReturnsZeroEarnedPoints() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.earnedPoints").value(0));
	}

	/** 같은 여행의 여러 EARN 내역 amount를 합산해 earnedPoints로 반환한다. */
	@Test
	@DisplayName("같은 여행의 여러 EARN은 합산한다")
	void multipleEarnsOnSameTripAreSummed() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);
		insertTransaction(member.memberId(), tripId, "EARN", 200, "미션 보상");
		insertTransaction(member.memberId(), tripId, "EARN", 100, "미션 보상");

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.earnedPoints").value(300));
	}

	/** 다른 여행 EARN과 같은 여행의 LEAVE_TO_BUYEO·EXPIRE·ADJUST는 earnedPoints에 넣지 않는다. */
	@Test
	@DisplayName("다른 여행 EARN과 같은 여행의 비EARN 내역은 earnedPoints에 넣지 않는다")
	void excludesOtherTripEarnAndNonEarnTypes() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS);
		UUID otherTripId = insertTrip(member.memberId(), "SETTLED", startedAt, startedAt.plus(20, ChronoUnit.MINUTES));
		insertTransaction(member.memberId(), otherTripId, "EARN", 1000, "다른 여행 적립");

		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);
		insertTransaction(member.memberId(), tripId, "EARN", 150, "미션 보상");
		insertTransaction(member.memberId(), tripId, "ADJUST", 50, "운영 조정");
		insertTransaction(member.memberId(), tripId, "EXPIRE", -20, "만료");
		insertTransaction(member.memberId(), tripId, "LEAVE_TO_BUYEO", -30, "부여에 남기기");

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.earnedPoints").value(150));
	}

	/** SETTLED이고 LEAVE_TO_BUYEO여도 earnedPoints는 그 여행의 EARN 합계 그대로다. */
	@Test
	@DisplayName("SETTLED+LEAVE_TO_BUYEO여도 earnedPoints는 그 여행 EARN 합계다")
	void settledLeaveToBuyeoStillReturnsEarnSum() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS);
		Instant endedAt = startedAt.plus(45, ChronoUnit.MINUTES);
		UUID tripId = insertTrip(member.memberId(), "SETTLED", startedAt, endedAt);
		insertTransaction(member.memberId(), tripId, "EARN", 200, "미션 보상");
		insertTransaction(member.memberId(), tripId, "EARN", 100, "미션 보상");
		insertTransaction(member.memberId(), tripId, "LEAVE_TO_BUYEO", -300, "부여에 남기기");
		insertLeaveToBuyeoSettlement(tripId, 300);

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.earnedPoints").value(300));
	}

	private ResultActions performGet(AuthenticatedMember member, UUID tripId) throws Exception {
		return mockMvc.perform(
				get("/trips/" + tripId + "/statistics").header("Authorization", "Bearer " + member.accessToken()));
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

	private UUID insertTrip(UUID memberId, String status, Instant startedAt, Instant endedAt) {
		UUID tripId = UUID.randomUUID();
		Instant settledAt = "SETTLED".equals(status) ? endedAt : null;
		jdbcTemplate.update(
				"INSERT INTO trips (id, member_id, status, started_at, ended_at, settled_at) "
						+ "VALUES (?, ?, ?::trip_status, ?, ?, ?)",
				tripId, memberId, status, Timestamp.from(startedAt == null ? Instant.now() : startedAt),
				endedAt == null ? null : Timestamp.from(endedAt), settledAt == null ? null : Timestamp.from(settledAt));
		return tripId;
	}

	private UUID insertPlace(String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate
				.update("INSERT INTO places (id, category, name, location) VALUES (?, 'HERITAGE'::place_category, ?, "
						+ "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)", id, name, 126.9, 36.2);
		return id;
	}

	private UUID insertMission(UUID placeId, String title) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO missions (id, place_id, location, type, title, description, reward_points, "
				+ "ox_correct_answer) VALUES (?, ?, (SELECT location FROM places WHERE id = ?), "
				+ "'OX'::mission_type, ?, '설명', 10, true)", id, placeId, placeId, title);
		return id;
	}

	private void insertVisitRecord(UUID tripId, UUID missionId, UUID placeId) {
		jdbcTemplate.update("INSERT INTO visit_records (id, trip_id, mission_id, place_id) VALUES (?, ?, ?, ?)",
				UUID.randomUUID(), tripId, missionId, placeId);
	}

	private void insertTransaction(UUID memberId, UUID tripId, String type, long amount, String description) {
		jdbcTemplate.update(
				"INSERT INTO point_transactions (id, member_id, trip_id, type, amount, description) "
						+ "VALUES (?, ?, ?, ?::point_transaction_type, ?, ?)",
				UUID.randomUUID(), memberId, tripId, type, amount, description);
	}

	private void insertLeaveToBuyeoSettlement(UUID tripId, long settledPoints) {
		jdbcTemplate.update("INSERT INTO point_settlements (id, trip_id, choice, settled_points, expires_at) "
				+ "VALUES (?, ?, 'LEAVE_TO_BUYEO', ?, NULL)", UUID.randomUUID(), tripId, settledPoints);
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
