package com.buyeoon.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** UC-14 미션 완료로 배지를 획득하는 흐름의 통합 테스트다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MissionBadgeAwardIntegrationTests {

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

	@Autowired
	private ObjectMapper objectMapper;

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

	/** 첫 완료로 threshold를 충족하면 response에 배지가 포함되고 획득 이력과 알림이 같은 트랜잭션에 저장된다. */
	@Test
	@DisplayName("미션 완료로 threshold를 처음 충족하면 배지·획득 이력·알림이 함께 생긴다")
	void firstMissionCompletionAwardsBadgeWithHistoryAndNotification() throws Exception {
		UUID badgeId = insertBadge("탐험가", "public/badges/explorer.png", "미션 1회 완료");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		MvcResult result = mockMvc.perform(submit(missionId, "award-first", oxRequest(tripId, true)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].name").value("탐험가"))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].condition").value("미션 1회 완료"))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].earnedAt").isNotEmpty())
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].imageUrl").isNotEmpty()).andReturn();

		JsonNode badge = objectMapper.readTree(result.getResponse().getContentAsString()).get("data")
				.get("newlyAwardedBadges").get(0);
		URI imageUri = URI.create(badge.get("imageUrl").stringValue());
		assertThat(imageUri.getPath()).isEqualTo("/public/badges/explorer.png");

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM member_badges WHERE member_id = ? AND badge_id = ? AND trip_id = ?",
				Integer.class, member.memberId(), badgeId, tripId)).isEqualTo(1);
		Map<String, Object> notification = jdbcTemplate.queryForMap(
				"SELECT title, body, target_type, target_id FROM notifications WHERE member_id = ? AND type = 'BADGE'",
				member.memberId());
		assertThat(notification.get("title")).isEqualTo("새로운 배지를 획득했어요!");
		assertThat(notification.get("body")).isEqualTo("탐험가 배지를 획득했어요.");
		assertThat(notification.get("target_type")).isEqualTo("BADGE");
		assertThat(notification.get("target_id")).isEqualTo(badgeId);
	}

	/** GET /members/me/notifications로도 같은 배지 알림을 조회할 수 있다. */
	@Test
	@DisplayName("배지 획득 알림은 알림 목록 조회에서도 확인할 수 있다")
	void badgeNotificationIsVisibleThroughNotificationsEndpoint() throws Exception {
		UUID badgeId = insertBadge("탐험가", null, "미션 1회 완료");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "award-notif", oxRequest(tripId, true))).andExpect(status().isOk());

		mockMvc.perform(get("/members/me/notifications").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].type").value("BADGE"))
				.andExpect(jsonPath("$.data.items[0].title").value("새로운 배지를 획득했어요!"))
				.andExpect(jsonPath("$.data.items[0].body").value("탐험가 배지를 획득했어요."))
				.andExpect(jsonPath("$.data.items[0].targetType").value("BADGE"))
				.andExpect(jsonPath("$.data.items[0].targetId").value(badgeId.toString()));
	}

	/** 이미지가 없는 배지는 imageUrl이 null이다. */
	@Test
	@DisplayName("이미지가 없는 배지는 imageUrl이 null이다")
	void badgeWithoutImageHasNullImageUrl() throws Exception {
		UUID badgeId = insertBadge("무이미지 배지", null, "미션 1회 완료");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "award-no-image", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].imageUrl").isEmpty());
	}

	/** threshold를 충족하지 못하면 newlyAwardedBadges는 빈 배열이다. */
	@Test
	@DisplayName("threshold를 충족하지 못하면 빈 배열을 반환한다")
	void belowThresholdReturnsEmptyArray() throws Exception {
		UUID badgeId = insertBadge("두 번 완료", null, "미션 2회 완료");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 2);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "below-threshold", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges").isArray())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isZero();
	}

	/** 다른 여행에서 같은 미션을 다시 완료하면 lifetime 완료 수가 다시 늘어 threshold를 충족시킬 수 있다. */
	@Test
	@DisplayName("다른 여행의 같은 미션 재완료는 lifetime 완료 수를 다시 늘린다")
	void completingSameMissionInAnotherTripIncreasesLifetimeCount() throws Exception {
		UUID badgeId = insertBadge("두 번 완료", null, "미션 2회 완료");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 2);
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		UUID firstTrip = startTrip(member.memberId());
		mockMvc.perform(submit(missionId, "lifetime-trip-1", oxRequest(firstTrip, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));
		endTrip(firstTrip);

		UUID secondTrip = startTrip(member.memberId());
		mockMvc.perform(submit(missionId, "lifetime-trip-2", oxRequest(secondTrip, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()));

		assertThat(jdbcTemplate.queryForObject("SELECT trip_id FROM member_badges WHERE member_id = ? AND badge_id = ?",
				UUID.class, member.memberId(), badgeId)).isEqualTo(secondTrip);
	}

	/** 여러 배지 조건을 함께 충족하면 badge ID 오름차순으로 반환한다. */
	@Test
	@DisplayName("여러 배지를 동시에 충족하면 badge ID 오름차순으로 반환한다")
	void multipleBadgesAreAwardedInBadgeIdAscendingOrder() throws Exception {
		UUID first = insertBadge("배지A", null, "미션 1회 완료");
		UUID second = insertBadge("배지B", null, "미션 1회 완료");
		insertCondition(first, "MISSION_COMPLETED_COUNT", 1);
		insertCondition(second, "MISSION_COMPLETED_COUNT", 1);
		// PostgreSQL의 uuid 오름차순은 Java UUID#compareTo와 다를 수 있으므로 DB 정렬 결과를 그대로 사용한다.
		List<UUID> ascending = jdbcTemplate.queryForList("SELECT id FROM badges ORDER BY id ASC", UUID.class);
		UUID expectedFirst = ascending.get(0);
		UUID expectedSecond = ascending.get(1);

		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "multi-badge", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(2)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(expectedFirst.toString()))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[1].badgeId").value(expectedSecond.toString()));
	}

	/** 이미 획득한 배지는 다시 지급되거나 알림을 다시 만들지 않고 최초 여행 연결을 유지한다. */
	@Test
	@DisplayName("이미 획득한 배지는 다시 지급되지 않고 최초 여행 연결을 유지한다")
	void alreadyEarnedBadgeIsNotAwardedAgain() throws Exception {
		UUID badgeId = insertBadge("탐험가", null, "미션 1회 완료");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID place = insertProjectedPlace("장소", 50);

		UUID firstTrip = startTrip(member.memberId());
		UUID firstMission = insertOxMission(place, "미션1", 100, null, true);
		mockMvc.perform(submit(firstMission, "earn-once", oxRequest(firstTrip, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)));
		endTrip(firstTrip);

		UUID secondTrip = startTrip(member.memberId());
		UUID secondMission = insertOxMission(place, "미션2", 100, null, true);
		mockMvc.perform(submit(secondMission, "earn-again", oxRequest(secondTrip, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT trip_id FROM member_badges WHERE badge_id = ?", UUID.class,
				badgeId)).isEqualTo(firstTrip);
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'BADGE'", Integer.class))
				.isEqualTo(1);
	}

	/** 지급이 중단된 배지는 조건을 충족해도 지급하지 않는다. */
	@Test
	@DisplayName("지급이 중단된 배지는 조건을 충족해도 지급하지 않는다")
	void retiredBadgeIsNeverAwarded() throws Exception {
		UUID badgeId = insertBadge("퇴역 배지", null, "미션 1회 완료");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		jdbcTemplate.update("UPDATE badges SET retired_at = now() WHERE id = ?", badgeId);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "retired-badge", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isZero();
	}

	/** 같은 배지 조건을 동시에 충족해도 획득 이력과 알림은 한 번만 생성된다. */
	@Test
	@DisplayName("동시에 같은 배지 조건을 충족해도 획득 이력과 알림은 한 번만 생성된다")
	void concurrentCompletionsAwardBadgeExactlyOnce() throws Exception {
		UUID badgeId = insertBadge("탐험가", null, "미션 1회 완료");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID firstMission = insertOxMission(place, "미션1", 100, null, true);
		UUID secondMission = insertOxMission(place, "미션2", 100, null, true);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor.submit(
					() -> concurrentSubmit(firstMission, "concurrent-badge-a", oxRequest(tripId, true), ready, start));
			Future<MvcResult> second = executor.submit(
					() -> concurrentSubmit(secondMission, "concurrent-badge-b", oxRequest(tripId, true), ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
			assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
			assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isEqualTo(1);
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'BADGE'", Integer.class))
				.isEqualTo(1);
	}

	/** 같은 멱등성 키의 재요청은 최초 지급 결과를 재사용하고 배지·알림을 다시 만들지 않지만 이미지 URL은 새로 생성한다. */
	@Test
	@DisplayName("같은 멱등성 키의 재요청은 최초 지급 결과를 재사용하고 부작용을 반복하지 않는다")
	void idempotentReplayReusesAwardResultWithoutDuplicatingSideEffects() throws Exception {
		UUID badgeId = insertBadge("탐험가", "public/badges/explorer.png", "미션 1회 완료");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);
		String key = "replay-badge-key";
		String body = oxRequest(tripId, true);

		MvcResult firstResult = mockMvc.perform(submit(missionId, key, body)).andExpect(status().isOk()).andReturn();
		MvcResult retriedResult = mockMvc.perform(submit(missionId, key, body)).andExpect(status().isOk()).andReturn();

		JsonNode firstBadge = objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("data")
				.get("newlyAwardedBadges").get(0);
		JsonNode retriedBadge = objectMapper.readTree(retriedResult.getResponse().getContentAsString()).get("data")
				.get("newlyAwardedBadges").get(0);
		assertThat(retriedBadge.get("badgeId").stringValue()).isEqualTo(firstBadge.get("badgeId").stringValue());
		assertThat(retriedBadge.get("earnedAt").stringValue()).isEqualTo(firstBadge.get("earnedAt").stringValue());
		assertThat(URI.create(retriedBadge.get("imageUrl").stringValue()).getPath())
				.isEqualTo(URI.create(firstBadge.get("imageUrl").stringValue()).getPath());

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isEqualTo(1);
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'BADGE'", Integer.class))
				.isEqualTo(1);
	}

	private MvcResult concurrentSubmit(UUID missionId, String key, String body, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return mockMvc.perform(submit(missionId, key, body)).andReturn();
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

	private UUID insertOxMission(UUID placeId, String title, int rewardPoints, Integer maxAttempts,
			boolean correctAnswer) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO missions (id, place_id, location, type, title, description, reward_points, "
						+ "max_attempts, ox_correct_answer) VALUES (?, ?, (SELECT location FROM places WHERE id = ?), "
						+ "'OX'::mission_type, ?, '설명', ?, ?, ?)",
				id, placeId, placeId, title, rewardPoints, maxAttempts, correctAnswer);
		return id;
	}

	private UUID insertBadge(String name, String imageKey, String conditionText) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO badges (id, category, name, description, image_key, condition_text) "
						+ "VALUES (?, 'EXPLORATION'::badge_category, ?, '설명', ?, ?)",
				id, name, imageKey, conditionText);
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
