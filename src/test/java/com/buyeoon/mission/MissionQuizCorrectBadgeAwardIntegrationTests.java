package com.buyeoon.mission;

import static org.assertj.core.api.Assertions.assertThat;
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

/** UC-14 퀴즈 정답 수 집계로 "백제 박사" 배지를 획득하는 흐름의 통합 테스트다(#185). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MissionQuizCorrectBadgeAwardIntegrationTests {

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
		// V18에서 지급 재개한 실제 "백제 박사" 배지가 같은 QUIZ_CORRECT_COUNT 조건을 쓰므로, 테스트가 삽입하는
		// 배지와 중복 지급되지 않도록 catalog를 비우고 시작한다.
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

	/** 퀴즈 15개를 정답으로 완료하면 "백제 박사" 배지를 마지막 제출 response에서 획득한다. */
	@Test
	@DisplayName("퀴즈 15개 정답 달성 시 백제 박사 배지를 획득한다")
	void fifteenthCorrectQuizAnswerAwardsBadge() throws Exception {
		UUID badgeId = insertBadge("백제 박사", "public/badges/quiz-master.png", "퀴즈 15개 정답");
		insertCondition(badgeId, "QUIZ_CORRECT_COUNT", 15);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);

		for (int index = 1; index <= 14; index++) {
			UUID missionId = insertOxMission(place, "OX 미션 " + index, 10, null, true);
			mockMvc.perform(submit(missionId, "quiz-correct-" + index, oxRequest(tripId, true)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));
		}

		UUID fifteenthMission = insertOxMission(place, "OX 미션 15", 10, null, true);
		mockMvc.perform(submit(fifteenthMission, "quiz-correct-15", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].name").value("백제 박사"));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM member_badges WHERE member_id = ? AND badge_id = ? AND trip_id = ?",
				Integer.class, member.memberId(), badgeId, tripId)).isEqualTo(1);
	}

	/** 정답 14개로는 threshold를 충족하지 못해 배지를 지급하지 않는다. */
	@Test
	@DisplayName("퀴즈 정답이 14개면 백제 박사 배지를 지급하지 않는다")
	void fourteenthCorrectQuizAnswerDoesNotAwardBadge() throws Exception {
		UUID badgeId = insertBadge("백제 박사", null, "퀴즈 15개 정답");
		insertCondition(badgeId, "QUIZ_CORRECT_COUNT", 15);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);

		for (int index = 1; index <= 14; index++) {
			UUID missionId = insertOxMission(place, "OX 미션 " + index, 10, null, true);
			mockMvc.perform(submit(missionId, "quiz-below-" + index, oxRequest(tripId, true)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));
		}

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isZero();
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

	private UUID insertOxMission(UUID placeId, String title, int rewardPoints, Integer maxAttempts,
			boolean correctAnswer) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO missions (id, place_id, type, title, description, reward_points, max_attempts, "
						+ "ox_correct_answer) VALUES (?, ?, 'OX'::mission_type, ?, '설명', ?, ?, ?)",
				id, placeId, title, rewardPoints, maxAttempts, correctAnswer);
		return id;
	}

	private UUID insertBadge(String name, String imageKey, String conditionText) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO badges (id, category, name, description, image_key, condition_text) "
				+ "VALUES (?, 'QUIZ'::badge_category, ?, '설명', ?, ?)", id, name, imageKey, conditionText);
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
