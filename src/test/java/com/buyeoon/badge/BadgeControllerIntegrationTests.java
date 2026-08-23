package com.buyeoon.badge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** UC-18 배지 진척도 목록 조회의 통합 테스트다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BadgeControllerIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private org.springframework.test.web.servlet.MockMvc mockMvc;

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
		jdbcTemplate.update("DELETE FROM member_badges");
		jdbcTemplate.update("DELETE FROM badge_conditions");
		jdbcTemplate.update("DELETE FROM badges");
		jdbcTemplate.update("DELETE FROM mission_participations");
		jdbcTemplate.update(
				"DELETE FROM missions WHERE place_id IN (SELECT id FROM places WHERE ST_Y(location::geometry) < -70)");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("지급 중단되지 않았거나 이미 획득한 배지를 200으로 반환하며 earnedCount·totalCount·items를 포함한다")
	void returnsVisibleBadgesWithCounts() throws Exception {
		UUID activeBadge = insertBadge("EXPLORATION", "탐험가", null, null);
		insertCondition(activeBadge, "MISSION_COMPLETED_COUNT", 1);
		UUID retiredEarnedBadge = insertBadge("EXPLORATION", "은퇴한 탐험가", null, Instant.now());
		insertCondition(retiredEarnedBadge, "MISSION_COMPLETED_COUNT", 1);
		insertMemberBadge(member.memberId(), retiredEarnedBadge, insertTrip(member.memberId()));
		UUID retiredUnearnedBadge = insertBadge("EXPLORATION", "지급 중단", null, Instant.now());
		insertCondition(retiredUnearnedBadge, "MISSION_COMPLETED_COUNT", 1);

		mockMvc.perform(badgesRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.totalCount").value(2)).andExpect(jsonPath("$.data.earnedCount").value(1))
				.andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[*].badgeId").value(
						org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(retiredUnearnedBadge.toString()))));
	}

	@Test
	@DisplayName("category로 필터링하면 해당 분야 배지만 포함되고 카운트도 필터링 기준이다")
	void filtersByCategory() throws Exception {
		UUID explorationBadge = insertBadge("EXPLORATION", "탐험가", null, null);
		insertCondition(explorationBadge, "MISSION_COMPLETED_COUNT", 1);
		UUID quizBadge = insertBadge("QUIZ", "퀴즈왕", null, null);
		insertCondition(quizBadge, "MISSION_COMPLETED_COUNT", 1);

		mockMvc.perform(badgesRequest().param("category", "QUIZ")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].badgeId").value(quizBadge.toString()))
				.andExpect(jsonPath("$.data.totalCount").value(1));
	}

	@Test
	@DisplayName("items는 분야 순서, 같은 분야 내에서는 배지 ID 오름차순으로 정렬된다")
	void sortsByCategoryThenBadgeId() throws Exception {
		UUID quizBadge = insertBadge("QUIZ", "퀴즈왕", null, null);
		insertCondition(quizBadge, "MISSION_COMPLETED_COUNT", 1);
		UUID explorationBadgeA = insertBadge("EXPLORATION", "탐험가A", null, null);
		insertCondition(explorationBadgeA, "MISSION_COMPLETED_COUNT", 1);
		UUID explorationBadgeB = insertBadge("EXPLORATION", "탐험가B", null, null);
		insertCondition(explorationBadgeB, "MISSION_COMPLETED_COUNT", 1);
		// PostgreSQL은 UUID를 부호 없는 바이트 순서로 비교하며, 이는 UUID#toString()의 문자열 순서와 일치한다.
		// java.util.UUID#compareTo는 최상위 64비트를 부호 있는 long으로 비교해 DB 순서와 다를 수 있다.
		boolean aFirst = explorationBadgeA.toString().compareTo(explorationBadgeB.toString()) < 0;
		UUID first = aFirst ? explorationBadgeA : explorationBadgeB;
		UUID second = aFirst ? explorationBadgeB : explorationBadgeA;

		mockMvc.perform(badgesRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].badgeId").value(first.toString()))
				.andExpect(jsonPath("$.data.items[1].badgeId").value(second.toString()))
				.andExpect(jsonPath("$.data.items[2].badgeId").value(quizBadge.toString()));
	}

	@Test
	@DisplayName("조건의 progress는 threshold를 초과해도 threshold로 캡되어 반환된다")
	void capsProgressAtThreshold() throws Exception {
		UUID badgeId = insertBadge("EXPLORATION", "탐험가", null, null);
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID tripId = insertTrip(member.memberId());
		UUID place = insertPlace();
		UUID missionA = insertMission(place);
		UUID missionB = insertMission(place);
		insertCompletedParticipation(tripId, missionA);
		insertCompletedParticipation(tripId, missionB);

		mockMvc.perform(badgesRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].conditions[0].progress").value(1))
				.andExpect(jsonPath("$.data.items[0].conditions[0].threshold").value(1))
				.andExpect(jsonPath("$.data.items[0].conditions[0].achieved").value(true));
	}

	@Test
	@DisplayName("모든 조건을 충족한 배지는 EARNED 상태이고 earnedAt이 채워진다")
	void returnsEarnedStatusWithEarnedAt() throws Exception {
		UUID badgeId = insertBadge("EXPLORATION", "탐험가", null, null);
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID tripId = insertTrip(member.memberId());
		insertMemberBadge(member.memberId(), badgeId, tripId);

		mockMvc.perform(badgesRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].status").value("EARNED"))
				.andExpect(jsonPath("$.data.items[0].earnedAt").exists());
	}

	@Test
	@DisplayName("진행값이 있는 미획득 배지는 IN_PROGRESS, 없으면 NOT_EARNED다")
	void returnsInProgressOrNotEarnedBasedOnProgress() throws Exception {
		UUID inProgressBadge = insertBadge("EXPLORATION", "진행중", null, null);
		insertCondition(inProgressBadge, "MISSION_COMPLETED_COUNT", 2);
		UUID tripId = insertTrip(member.memberId());
		UUID place = insertPlace();
		UUID mission = insertMission(place);
		insertCompletedParticipation(tripId, mission);
		// 서로 다른 metric을 사용해 미션 완료 활동이 이 배지의 진행값에 영향을 주지 않게 한다.
		UUID notEarnedBadge = insertBadge("QUIZ", "미획득", null, null);
		insertCondition(notEarnedBadge, "HERITAGE_VISITED_COUNT", 5);

		mockMvc.perform(badgesRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.data.items[1].status").value("NOT_EARNED"));
	}

	@Test
	@DisplayName("알 수 없는 category는 400을 반환한다")
	void returns400WhenCategoryIsInvalid() throws Exception {
		mockMvc.perform(badgesRequest().param("category", "BANANA")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("인증되지 않은 요청은 401을 반환한다")
	void returns401WhenUnauthenticated() throws Exception {
		mockMvc.perform(get("/members/me/badges")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("imageKey가 있으면 imageUrl에 presigned URL을, 없으면 null을 반환한다")
	void returnsPresignedImageUrlOrNull() throws Exception {
		UUID withImage = insertBadge("EXPLORATION", "이미지 있음", "public/badges/example.png", null);
		insertCondition(withImage, "MISSION_COMPLETED_COUNT", 1);
		UUID withoutImage = insertBadge("QUIZ", "이미지 없음", null, null);
		insertCondition(withoutImage, "MISSION_COMPLETED_COUNT", 1);

		mockMvc.perform(badgesRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].imageUrl", org.hamcrest.Matchers.containsString("example.png")))
				.andExpect(jsonPath("$.data.items[1].imageUrl").value(org.hamcrest.Matchers.nullValue()));
	}

	private MockHttpServletRequestBuilder badgesRequest() {
		return get("/members/me/badges").header("Authorization", "Bearer " + member.accessToken());
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

	private UUID insertPlace() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO places (id, category, name, location) VALUES (?, 'HERITAGE'::place_category, '장소', "
						+ "ST_SetSRID(ST_MakePoint(0, -75), 4326)::geography)",
				id);
		return id;
	}

	private UUID insertMission(UUID placeId) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO missions (id, place_id, location, type, title, description, reward_points)
				VALUES (?, ?, (SELECT location FROM places WHERE id = ?), 'PHOTO', '테스트 미션', '설명', 100)
				""", id, placeId, placeId);
		return id;
	}

	private void insertCompletedParticipation(UUID tripId, UUID missionId) {
		jdbcTemplate.update("""
				INSERT INTO mission_participations (trip_id, mission_id, status, attempt_count, completed_at)
				VALUES (?, ?, 'COMPLETED', 1, CURRENT_TIMESTAMP)
				""", tripId, missionId);
	}

	private UUID insertBadge(String category, String name, String imageKey, Instant retiredAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO badges (id, category, name, description, image_key, condition_text, retired_at) "
						+ "VALUES (?, ?::badge_category, ?, '설명', ?, '조건', ?)",
				id, category, name, imageKey, retiredAt == null ? null : Timestamp.from(retiredAt));
		return id;
	}

	private void insertCondition(UUID badgeId, String metricKey, long threshold) {
		jdbcTemplate.update("INSERT INTO badge_conditions (badge_id, metric_key, threshold) VALUES (?, ?, ?)", badgeId,
				metricKey, threshold);
	}

	private void insertMemberBadge(UUID memberId, UUID badgeId, UUID tripId) {
		jdbcTemplate.update("INSERT INTO member_badges (member_id, badge_id, trip_id) VALUES (?, ?, ?)", memberId,
				badgeId, tripId);
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
