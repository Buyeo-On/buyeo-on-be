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
import java.util.List;
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

/** UC-14 문화재 방문·복합 조건 배지 획득 흐름의 통합 테스트다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MissionHeritageBadgeAwardIntegrationTests {

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

	/**
	 * 새 문화재 visit이 생성되며 heritage threshold를 처음 충족하면 같은 response에서 heritage badge를
	 * 받는다.
	 */
	@Test
	@DisplayName("새 문화재 방문으로 heritage threshold를 처음 충족하면 같은 response에서 배지를 받는다")
	void firstHeritageVisitAwardsHeritageBadgeInSameResponse() throws Exception {
		UUID badgeId = insertBadge("문화탐방가", "미문화재 1곳 방문");
		insertCondition(badgeId, "HERITAGE_VISITED_COUNT", 1);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소A", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, true);

		mockMvc.perform(submit(missionId, "heritage-first", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.visitRecorded").value(true))
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM member_badges WHERE member_id = ? AND badge_id = ? AND trip_id = ?",
				Integer.class, member.memberId(), badgeId, tripId)).isEqualTo(1);
	}

	/**
	 * 같은 mission 완료에서 새로 생긴 mission participation과 visit row가 같은 transaction에서 함께
	 * 판정된다.
	 */
	@Test
	@DisplayName("같은 완료에서 생성한 participation과 visit row가 모두 metric 계산에 포함된다")
	void sameCompletionCountsBothNewParticipationAndVisitRow() throws Exception {
		UUID badgeId = insertBadge("첫 걸음", "미션 1회 완료 및 문화재 1곳 방문");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		insertCondition(badgeId, "HERITAGE_VISITED_COUNT", 1);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소A", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, true);

		mockMvc.perform(submit(missionId, "both-metrics", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()));
	}

	/** 같은 여행, 같은 문화재의 다른 미션 완료로 visit이 새로 생기지 않으면 heritage metric을 다시 판정하지 않는다. */
	@Test
	@DisplayName("같은 문화재의 다른 미션 완료로 visit이 새로 생기지 않으면 heritage 배지를 다시 판정하지 않는다")
	void completingAnotherMissionAtSamePlaceWithoutNewVisitDoesNotRejudgeHeritage() throws Exception {
		UUID badgeId = insertBadge("문화탐방가", "문화재 2곳 방문");
		insertCondition(badgeId, "HERITAGE_VISITED_COUNT", 2);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소A", 50);
		UUID firstMission = insertOxMission(place, "미션1", 100, true);
		UUID secondMission = insertOxMission(place, "미션2", 100, true);

		mockMvc.perform(submit(firstMission, "same-place-1", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.visitRecorded").value(true))
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		mockMvc.perform(submit(secondMission, "same-place-2", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.visitRecorded").value(false))
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isZero();
	}

	/** 다른 여행에서 같은 문화재를 재방문해도 lifetime 고유 문화재 수는 늘지 않고, 서로 다른 문화재의 최초 방문만 늘린다. */
	@Test
	@DisplayName("다른 여행의 재방문은 증가시키지 않고 서로 다른 문화재의 최초 방문만 lifetime 수를 늘린다")
	void revisitingSamePlaceInAnotherTripDoesNotIncreaseLifetimeCountButDifferentPlaceDoes() throws Exception {
		UUID badgeId = insertBadge("문화탐방가", "문화재 2곳 방문");
		insertCondition(badgeId, "HERITAGE_VISITED_COUNT", 2);
		UUID placeA = insertProjectedPlace("장소A", 50);
		UUID placeB = insertProjectedPlace("장소B", 60);

		UUID firstTrip = startTrip(member.memberId());
		UUID missionAtPlaceAFirst = insertOxMission(placeA, "미션A1", 100, true);
		mockMvc.perform(submit(missionAtPlaceAFirst, "revisit-trip-1", oxRequest(firstTrip, true)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.visitRecorded").value(true))
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));
		endTrip(firstTrip);

		UUID secondTrip = startTrip(member.memberId());
		UUID missionAtPlaceASecond = insertOxMission(placeA, "미션A2", 100, true);
		mockMvc.perform(submit(missionAtPlaceASecond, "revisit-trip-2", oxRequest(secondTrip, true)))
				.andExpect(status().isOk())
				// 다른 여행이므로 visit row는 새로 생기지만, 같은 문화재라 고유 방문 수는 늘지 않는다.
				.andExpect(jsonPath("$.data.visitRecorded").value(true))
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		UUID missionAtPlaceB = insertOxMission(placeB, "미션B", 100, true);
		mockMvc.perform(submit(missionAtPlaceB, "revisit-trip-2-place-b", oxRequest(secondTrip, true)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.visitRecorded").value(true))
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()));
	}

	/** Mission과 heritage 조건을 함께 가진 배지는 두 조건이 모두 threshold 이상인 activity에서만 지급한다. */
	@Test
	@DisplayName("복합 조건 배지는 두 조건을 모두 충족하는 activity에서만 지급한다")
	void compositeBadgeIsAwardedOnlyWhenBothConditionsAreMet() throws Exception {
		UUID badgeId = insertBadge("탐험 마스터", "미션 2회 완료 및 문화재 2곳 방문");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 2);
		insertCondition(badgeId, "HERITAGE_VISITED_COUNT", 2);
		UUID placeA = insertProjectedPlace("장소A", 50);
		UUID placeB = insertProjectedPlace("장소B", 60);
		UUID tripId = startTrip(member.memberId());
		UUID missionA1 = insertOxMission(placeA, "미션A1", 100, true);
		UUID missionA2 = insertOxMission(placeA, "미션A2", 100, true);
		UUID missionB = insertOxMission(placeB, "미션B", 100, true);

		// mission count=1, heritage count=1: 아직 어느 조건도 충족하지 않는다.
		mockMvc.perform(submit(missionA1, "composite-1", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		// mission count=2(충족), heritage count=1(미충족, 같은 장소라 visit 미생성): 지급하지 않는다.
		mockMvc.perform(submit(missionA2, "composite-2", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.visitRecorded").value(false))
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		// mission count=3, heritage count=2(충족): 두 조건이 모두 threshold 이상이 되어 지급한다.
		mockMvc.perform(submit(missionB, "composite-3", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.visitRecorded").value(true))
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()));
	}

	/** Mission badge와 heritage badge가 동시에 지급돼도 badge ID 오름차순이며 이력·알림은 배지당 한 건이다. */
	@Test
	@DisplayName("mission 배지와 heritage 배지가 동시에 지급되면 badge ID 오름차순이고 배지당 이력·알림이 한 건이다")
	void missionAndHeritageBadgesAwardedTogetherAreOrderedAndRecordedOnce() throws Exception {
		UUID missionBadgeId = insertBadge("탐험가", "미션 1회 완료");
		insertCondition(missionBadgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID heritageBadgeId = insertBadge("문화탐방가", "문화재 1곳 방문");
		insertCondition(heritageBadgeId, "HERITAGE_VISITED_COUNT", 1);
		List<UUID> ascending = jdbcTemplate.queryForList("SELECT id FROM badges WHERE id IN (?, ?) ORDER BY id ASC",
				UUID.class, missionBadgeId, heritageBadgeId);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소A", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, true);

		mockMvc.perform(submit(missionId, "both-badges", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(2)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(ascending.get(0).toString()))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[1].badgeId").value(ascending.get(1).toString()));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE member_id = ?", Integer.class,
				member.memberId())).isEqualTo(2);
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'BADGE'", Integer.class))
				.isEqualTo(2);
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

	private void endTrip(UUID tripId) {
		jdbcTemplate.update("UPDATE trips SET status = 'ENDED', ended_at = now() WHERE id = ?", tripId);
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
				"INSERT INTO missions (id, place_id, type, title, description, reward_points, max_attempts, "
						+ "ox_correct_answer) VALUES (?, ?, 'OX'::mission_type, ?, '설명', ?, NULL, ?)",
				id, placeId, title, rewardPoints, correctAnswer);
		return id;
	}

	private UUID insertBadge(String name, String conditionText) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO badges (id, category, name, description, condition_text) "
				+ "VALUES (?, 'EXPLORATION'::badge_category, ?, '설명', ?)", id, name, conditionText);
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
