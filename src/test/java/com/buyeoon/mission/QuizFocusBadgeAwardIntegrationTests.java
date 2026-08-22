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

/** #188 배지 '집중력'(60분 내 퀴즈 10개 정답) 판정의 통합 테스트다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class QuizFocusBadgeAwardIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	private static final String METRIC_KEY = "QUIZ_CORRECT_WITHIN_60_MINUTES_COUNT";

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
		// 시드 마이그레이션이 등록한 '집중력' 배지 조건과 격리하기 위해, 테스트 전용 badge를 넣기 전에 catalog를 비운다.
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

	/** 과거 9개 정답에 이어 60분 이내에 10번째 정답을 제출하면 배지를 획득한다. */
	@Test
	@DisplayName("60분 이내에 퀴즈 10개를 정답으로 제출하면 배지를 획득한다")
	void tenCorrectAnswersWithin60MinutesAwardsBadge() throws Exception {
		UUID badgeId = insertBadge("집중력", "60분 내에 퀴즈 10개 정답");
		insertCondition(badgeId, 10);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);

		Instant base = Instant.now().minus(50, ChronoUnit.MINUTES);
		for (int i = 0; i < 9; i++) {
			recordPastCorrectQuizAnswer(tripId, place, base.plus(i, ChronoUnit.MINUTES));
		}

		UUID lastMissionId = insertOxMission(place, "퀴즈10", true);
		mockMvc.perform(submit(lastMissionId, "quiz-focus-award", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isEqualTo(1);
	}

	/** 정답 수가 10개여도 60분 윈도우를 벗어난 정답이 섞여 있으면 어느 60분 구간도 10개를 채우지 못해 배지를 획득하지 않는다. */
	@Test
	@DisplayName("60분 윈도우를 벗어난 정답이 섞여 있으면 배지를 획득하지 않는다")
	void answersSpreadOutsideWindowDoNotAwardBadge() throws Exception {
		UUID badgeId = insertBadge("집중력", "60분 내에 퀴즈 10개 정답");
		insertCondition(badgeId, 10);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);

		// 정답 9개를 서로 2시간씩 떨어뜨려 배치하면, 어떤 60분 구간에도 최대 1개의 과거 정답만 들어간다.
		Instant base = Instant.now().minus(20, ChronoUnit.HOURS);
		for (int i = 0; i < 9; i++) {
			recordPastCorrectQuizAnswer(tripId, place, base.plus(i * 2L, ChronoUnit.HOURS));
		}

		UUID lastMissionId = insertOxMission(place, "퀴즈10", true);
		mockMvc.perform(submit(lastMissionId, "quiz-focus-no-award", oxRequest(tripId, true)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isZero();
	}

	private void recordPastCorrectQuizAnswer(UUID tripId, UUID placeId, Instant submittedAt) {
		UUID missionId = insertOxMission(placeId, "과거 퀴즈 " + submittedAt, true);
		UUID participationId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO mission_participations (id, trip_id, mission_id, status, attempt_count, completed_at) "
						+ "VALUES (?, ?, ?, 'COMPLETED'::mission_status, 1, ?)",
				participationId, tripId, missionId, Timestamp.from(submittedAt));
		jdbcTemplate.update(
				"INSERT INTO mission_submissions (participation_id, type, ox_answer, correct, submitted_at) "
						+ "VALUES (?, 'OX'::mission_type, true, true, ?)",
				participationId, Timestamp.from(submittedAt));
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

	private UUID insertOxMission(UUID placeId, String title, boolean correctAnswer) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO missions (id, place_id, type, title, description, reward_points, ox_correct_answer) "
						+ "VALUES (?, ?, 'OX'::mission_type, ?, '설명', 10, ?)",
				id, placeId, title, correctAnswer);
		return id;
	}

	private UUID insertBadge(String name, String conditionText) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO badges (id, category, name, description, condition_text) "
				+ "VALUES (?, 'QUIZ'::badge_category, ?, '설명', ?)", id, name, conditionText);
		return id;
	}

	private void insertCondition(UUID badgeId, long threshold) {
		jdbcTemplate.update("INSERT INTO badge_conditions (badge_id, metric_key, threshold) VALUES (?, ?, ?)", badgeId,
				METRIC_KEY, threshold);
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
