package com.buyeoon.mission;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** UC-14 퀴즈 연속 정답(QUIZ_CORRECT_STREAK) 배지 획득 흐름의 통합 테스트다(#187). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MissionQuizStreakBadgeAwardIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	// 시드 마이그레이션이 부여 지역에 장소·미션 예시 데이터를 채워두므로, 격리를 위해 남극 인근 좌표를
	// 기준으로 테스트 데이터를 배치한다.
	private static final double ORIGIN_LATITUDE = -75.0;
	private static final double ORIGIN_LONGITUDE = 0.0;

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

	private AuthenticatedMember member;

	@BeforeAll
	static void configureAwsCredentials() {
		System.setProperty("aws.accessKeyId", "test-access-key");
		System.setProperty("aws.secretAccessKey", "test-secret-key");
	}

	@AfterAll
	static void clearAwsCredentials() {
		System.clearProperty("aws.accessKeyId");
		System.clearProperty("aws.secretAccessKey");
	}

	@BeforeEach
	void setUpMember() {
		// 시드 마이그레이션이 '무결점' 배지를 QUIZ_CORRECT_STREAK=5 조건으로 이미 등록해뒀으므로, 테스트가
		// 직접 구성하는 조건과 중복 지급되지 않도록 기존 catalog를 먼저 비운다.
		jdbcTemplate.update("DELETE FROM badge_conditions");
		jdbcTemplate.update("DELETE FROM badges");
		member = insertAuthenticatedMember();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM member_badges");
		jdbcTemplate.update("DELETE FROM badge_conditions");
		jdbcTemplate.update("DELETE FROM badges");
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM visit_records");
		jdbcTemplate.update("DELETE FROM mission_submissions");
		jdbcTemplate.update("DELETE FROM mission_participations");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate
				.update("DELETE FROM mission_choices WHERE mission_id IN (SELECT id FROM missions WHERE place_id IN "
						+ "(SELECT id FROM places WHERE ST_Y(location::geometry) < -70))");
		jdbcTemplate.update(
				"DELETE FROM missions WHERE place_id IN (SELECT id FROM places WHERE ST_Y(location::geometry) < -70)");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 퀴즈 5개를 연속으로 정답 처리하면 5번째 정답에서 처음 배지를 획득한다. */
	@Test
	@DisplayName("퀴즈 5개를 연속으로 정답 처리하면 5번째 정답에서 배지를 획득한다")
	void fiveConsecutiveCorrectAnswersAwardsBadgeOnTheFifth() throws Exception {
		UUID badgeId = insertBadge("무결점", "퀴즈 5개 연속 정답");
		insertCondition(badgeId, "QUIZ_CORRECT_STREAK", 5);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);

		for (int i = 1; i <= 4; i++) {
			UUID missionId = insertOxMission(place, "정답 미션 " + i, 100, true);
			submitAndExpect(missionId, "streak-correct-" + i, oxRequest(tripId, true), 0);
		}

		UUID fifthMission = insertOxMission(place, "정답 미션 5", 100, true);
		submitAndExpect(fifthMission, "streak-correct-5", oxRequest(tripId, true), 1, badgeId);
	}

	/** 연속 중간에 오답이 섞이면 그때까지의 연속은 끊기고, 오답 이후 다시 5개를 연속으로 맞혀야 배지를 획득한다. */
	@Test
	@DisplayName("오답이 섞이면 연속이 끊기고 오답 이후 다시 5개를 연속으로 맞혀야 배지를 획득한다")
	void incorrectAnswerBreaksStreakAndRequiresANewConsecutiveRun() throws Exception {
		UUID badgeId = insertBadge("무결점", "퀴즈 5개 연속 정답");
		insertCondition(badgeId, "QUIZ_CORRECT_STREAK", 5);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);

		// 정답 3회로 연속 3을 만든다.
		for (int i = 1; i <= 3; i++) {
			UUID missionId = insertOxMission(place, "정답 미션 " + i, 100, true);
			submitAndExpect(missionId, "streak-break-correct-" + i, oxRequest(tripId, true), 0);
		}

		// 오답 1회로 연속이 끊긴다.
		UUID wrongMission = insertOxMission(place, "오답 미션", 100, true);
		submitAndExpect(wrongMission, "streak-break-wrong", oxRequest(tripId, false), 0);

		// 오답 이후 4개를 연속으로 맞혀도 아직 5에 도달하지 못한다.
		for (int i = 1; i <= 4; i++) {
			UUID missionId = insertOxMission(place, "재도전 미션 " + i, 100, true);
			submitAndExpect(missionId, "streak-break-retry-" + i, oxRequest(tripId, true), 0);
		}

		// 오답 이후 5번째 연속 정답에서 처음으로 배지를 획득한다.
		UUID fifthRetryMission = insertOxMission(place, "재도전 미션 5", 100, true);
		submitAndExpect(fifthRetryMission, "streak-break-retry-5", oxRequest(tripId, true), 1, badgeId);
	}

	private void submitAndExpect(UUID missionId, String idempotencyKey, String body, int expectedBadgeCount)
			throws Exception {
		mockMvc.perform(submit(missionId, idempotencyKey, body)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(expectedBadgeCount)));
	}

	private void submitAndExpect(UUID missionId, String idempotencyKey, String body, int expectedBadgeCount,
			UUID expectedBadgeId) throws Exception {
		mockMvc.perform(submit(missionId, idempotencyKey, body)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(expectedBadgeCount)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(expectedBadgeId.toString()));
	}

	private MockHttpServletRequestBuilder submit(UUID missionId, String idempotencyKey, String body) {
		return post("/missions/{missionId}/submissions", missionId)
				.header("Authorization", "Bearer " + member.accessToken()).header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String oxRequest(UUID tripId, boolean oxAnswer) {
		return "{\"tripId\":\"" + tripId + "\",\"type\":\"OX\",\"oxAnswer\":" + oxAnswer + ",\"location\":"
				+ locationJson() + "}";
	}

	private String locationJson() {
		return "{\"latitude\":" + ORIGIN_LATITUDE + ",\"longitude\":" + ORIGIN_LONGITUDE
				+ ",\"accuracyMeters\":5.5,\"capturedAt\":\"2026-08-12T15:30:00+09:00\"}";
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

	private UUID startTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status) VALUES (?, ?, 'IN_PROGRESS')", tripId, memberId);
		return tripId;
	}

	private UUID insertPlace(String name, double latitude, double longitude) {
		UUID id = UUID.randomUUID();
		jdbcTemplate
				.update("INSERT INTO places (id, category, name, location) VALUES (?, 'HERITAGE'::place_category, ?, "
						+ "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)", id, name, longitude, latitude);
		return id;
	}

	/**
	 * 원점에서 지정한 거리(m)만큼 떨어진 지점에 장소를 만든다. 삽입과 조회가 같은 PostGIS geodesic 계산을 쓰므로 왕복 오차가
	 * 없다.
	 */
	private UUID insertProjectedPlace(String name, double distanceMeters) {
		Map<String, Object> point = jdbcTemplate.queryForMap(
				"SELECT ST_Y(pt::geometry) AS lat, ST_X(pt::geometry) AS lon FROM "
						+ "(SELECT ST_Project(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, 0) AS pt) t",
				ORIGIN_LONGITUDE, ORIGIN_LATITUDE, distanceMeters);
		return insertPlace(name, ((Number) point.get("lat")).doubleValue(), ((Number) point.get("lon")).doubleValue());
	}

	private UUID insertOxMission(UUID placeId, String title, int rewardPoints, boolean correctAnswer) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO missions (id, place_id, location, type, title, description, reward_points, "
						+ "max_attempts, ox_correct_answer) VALUES (?, ?, (SELECT location FROM places WHERE id = ?), "
						+ "'OX'::mission_type, ?, '설명', ?, NULL, ?)",
				id, placeId, placeId, title, rewardPoints, correctAnswer);
		return id;
	}

	private UUID insertBadge(String name, String conditionText) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO badges (id, category, name, description, condition_text) "
				+ "VALUES (?, 'QUIZ'::badge_category, ?, '설명', ?)", id, name, conditionText);
		return id;
	}

	private void insertCondition(UUID badgeId, String metricKey, long threshold) {
		jdbcTemplate.update("INSERT INTO badge_conditions (badge_id, metric_key, threshold) VALUES (?, ?, ?)", badgeId,
				metricKey, threshold);
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
		registry.add("storage.images.bucket", () -> "buyeoon-test-images");
		registry.add("storage.images.region", () -> "ap-northeast-2");
	}

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
