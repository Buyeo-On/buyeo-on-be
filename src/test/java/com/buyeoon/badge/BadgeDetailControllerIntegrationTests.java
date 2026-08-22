package com.buyeoon.badge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** UC-18 배지 상세 조회의 통합 테스트다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BadgeDetailControllerIntegrationTests {

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
	@DisplayName("획득한 배지는 EARNED 상태와 earnedAt을 포함해 200으로 반환한다")
	void returnsEarnedBadgeDetail() throws Exception {
		UUID badgeId = insertBadge("EXPLORATION", "탐험가", "public/badges/example.png", null);
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID tripId = insertTrip(member.memberId());
		insertMemberBadge(member.memberId(), badgeId, tripId);

		mockMvc.perform(badgeRequest(badgeId)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.badgeId").value(badgeId.toString()))
				.andExpect(jsonPath("$.data.status").value("EARNED")).andExpect(jsonPath("$.data.earnedAt").exists())
				.andExpect(jsonPath("$.data.imageUrl", org.hamcrest.Matchers.containsString("example.png")));
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
		UUID notEarnedBadge = insertBadge("QUIZ", "미획득", null, null);
		insertCondition(notEarnedBadge, "HERITAGE_VISITED_COUNT", 5);

		mockMvc.perform(badgeRequest(inProgressBadge)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.data.earnedAt").value(org.hamcrest.Matchers.nullValue()));
		mockMvc.perform(badgeRequest(notEarnedBadge)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("NOT_EARNED"));
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

		mockMvc.perform(badgeRequest(badgeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.conditions[0].progress").value(1))
				.andExpect(jsonPath("$.data.conditions[0].threshold").value(1))
				.andExpect(jsonPath("$.data.conditions[0].achieved").value(true));
	}

	@Test
	@DisplayName("지급이 중단됐고 아직 획득하지 않은 배지를 상세 조회하면 404를 반환한다")
	void returns404ForRetiredUnearnedBadge() throws Exception {
		UUID retiredUnearnedBadge = insertBadge("EXPLORATION", "지급 중단", null, Instant.now());
		insertCondition(retiredUnearnedBadge, "MISSION_COMPLETED_COUNT", 1);

		mockMvc.perform(badgeRequest(retiredUnearnedBadge)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("지급이 중단됐지만 이미 획득한 배지는 상세 조회에 계속 노출된다")
	void returnsRetiredEarnedBadge() throws Exception {
		UUID retiredEarnedBadge = insertBadge("EXPLORATION", "은퇴한 탐험가", null, Instant.now());
		insertCondition(retiredEarnedBadge, "MISSION_COMPLETED_COUNT", 1);
		insertMemberBadge(member.memberId(), retiredEarnedBadge, insertTrip(member.memberId()));

		mockMvc.perform(badgeRequest(retiredEarnedBadge)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("EARNED"));
	}

	@Test
	@DisplayName("존재하지 않는 badgeId로 상세 조회하면 404를 반환한다")
	void returns404ForUnknownBadgeId() throws Exception {
		mockMvc.perform(badgeRequest(UUID.randomUUID())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("인증되지 않은 요청은 401을 반환한다")
	void returns401WhenUnauthenticated() throws Exception {
		mockMvc.perform(get("/members/me/badges/{badgeId}", UUID.randomUUID())).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("imageKey가 없으면 imageUrl은 null이다")
	void returnsNullImageUrlWhenImageKeyAbsent() throws Exception {
		UUID badgeId = insertBadge("QUIZ", "이미지 없음", null, null);
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);

		mockMvc.perform(badgeRequest(badgeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imageUrl").value(org.hamcrest.Matchers.nullValue()));
	}

	private MockHttpServletRequestBuilder badgeRequest(UUID badgeId) {
		return get("/members/me/badges/{badgeId}", badgeId).header("Authorization", "Bearer " + member.accessToken());
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
				INSERT INTO missions (id, place_id, type, title, description, reward_points)
				VALUES (?, ?, 'PHOTO', '테스트 미션', '설명', 100)
				""", id, placeId);
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
