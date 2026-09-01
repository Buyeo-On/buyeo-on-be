package com.buyeoon.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.common.storage.MissionPhotoObjectStore;
import com.buyeoon.common.storage.MissionPhotoObjectStore.MissionPhotoObject;
import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #189 복합 조건 배지 '백제 문화유산 완주자'({@code 30000000-0000-4000-8000-000000000007})의 통합
 * 테스트다. V17·V23 migration이 시딩한 실제 catalog 배지를 그대로 사용하고, 문화유산 15곳 방문·퀴즈 15개
 * 정답·인증 사진 15회 제출을 모두 충족했을 때만 배지가 지급되는지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MissionCompletionistBadgeAwardIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	private static final UUID COMPLETIONIST_BADGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000007");

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

	@MockitoBean
	private MissionPhotoObjectStore photoObjectStore;

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
		// V17·V23이 시딩한 배지 catalog는 다른 테스트 메서드도 재사용하므로 badges/badge_conditions는
		// 지우지 않는다.
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM member_badges");
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM visit_records");
		jdbcTemplate.update("DELETE FROM mission_submissions");
		jdbcTemplate.update("DELETE FROM mission_photos");
		jdbcTemplate.update("DELETE FROM mission_participations");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update(
				"DELETE FROM missions WHERE place_id IN (SELECT id FROM places WHERE ST_Y(location::geometry) < -70)");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/**
	 * 문화유산 15곳 방문·퀴즈 15개 정답·인증 사진 15회 제출을 모두 충족했을 때만 '백제 문화유산 완주자' 배지를 획득한다.
	 */
	@Test
	@DisplayName("문화유산 15곳·퀴즈 15개·인증 사진 15회를 모두 달성하면 '백제 문화유산 완주자' 배지를 획득한다")
	void awardsCompletionistBadgeOnlyWhenAllThreeConditionsAreMet() throws Exception {
		UUID tripId = startTrip(member.memberId());

		// 15곳의 고유 문화재에서 각각 OX 퀴즈를 정답으로 완료한다.
		// 이 과정에서 HERITAGE_VISITED_COUNT=15, QUIZ_CORRECT_COUNT=15를 함께 채운다.
		for (int i = 1; i <= 15; i++) {
			UUID place = insertProjectedPlace("문화재 " + i, 5 + i * 1.0);
			submitOx(tripId, place, i).andExpect(status().isOk());
		}

		// 인증 사진이 아직 0회이므로 세 조건을 모두 충족하지 못해 배지를 지급하지 않는다.
		assertThat(memberBadgeCount(COMPLETIONIST_BADGE_ID)).isZero();

		// 인증 사진 14회까지는 세 조건을 모두 충족하지 못한다.
		UUID photoPlace = insertProjectedPlace("사진 장소", 20);
		for (int i = 1; i <= 14; i++) {
			submitPhoto(tripId, photoPlace, i).andExpect(status().isOk());
		}
		assertThat(memberBadgeCount(COMPLETIONIST_BADGE_ID)).isZero();

		// 15번째 인증 사진으로 PHOTO_SUBMISSION_COUNT=15를 채우는 순간 배지를 획득한다.
		submitPhoto(tripId, photoPlace, 15).andExpect(status().isOk()).andExpect(
				jsonPath("$.data.newlyAwardedBadges[?(@.badgeId == '" + COMPLETIONIST_BADGE_ID + "')]", hasSize(1)));

		assertThat(memberBadgeCount(COMPLETIONIST_BADGE_ID)).isEqualTo(1);
		assertThat(badgeName(COMPLETIONIST_BADGE_ID)).isEqualTo("백제 문화유산 완주자");
		assertThat(notificationCount(COMPLETIONIST_BADGE_ID)).isEqualTo(1);
	}

	private ResultActions submitOx(UUID tripId, UUID place, int index) throws Exception {
		UUID missionId = insertOxMission(place, "OX 미션 " + index, 10);
		return mockMvc.perform(submit(missionId, "ox-submit-" + index, oxRequest(tripId)));
	}

	private ResultActions submitPhoto(UUID tripId, UUID place, int index) throws Exception {
		UUID missionId = insertPhotoMission(place, "사진 미션 " + index, 10);
		UUID photoId = UUID.randomUUID();
		stubMatchingPhoto(tripId, missionId, photoId, member.memberId(), "image/jpeg", 1024);
		return mockMvc.perform(submit(missionId, "photo-submit-" + index, photoRequest(tripId, photoId)));
	}

	private void stubMatchingPhoto(UUID tripId, UUID missionId, UUID photoId, UUID ownerId, String contentType,
			long fileSizeBytes) {
		String objectKey = "private/missions/" + tripId + "/" + missionId + "/" + photoId;
		when(photoObjectStore.head(objectKey)).thenReturn(
				Optional.of(new MissionPhotoObject(ownerId, contentType, fileSizeBytes, contentType, fileSizeBytes)));
	}

	private MockHttpServletRequestBuilder submit(UUID missionId, String idempotencyKey, String body) {
		return post("/missions/{missionId}/submissions", missionId)
				.header("Authorization", "Bearer " + member.accessToken()).header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String oxRequest(UUID tripId) {
		return "{\"tripId\":\"" + tripId + "\",\"type\":\"OX\",\"oxAnswer\":true,\"location\":" + locationJson() + "}";
	}

	private String photoRequest(UUID tripId, UUID photoId) {
		return "{\"tripId\":\"" + tripId + "\",\"type\":\"PHOTO\",\"photoId\":\"" + photoId + "\",\"location\":"
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

	private UUID insertPhotoMission(UUID placeId, String title, int rewardPoints) {
		return insertMission(placeId, "PHOTO", title, rewardPoints, null, null);
	}

	private UUID insertOxMission(UUID placeId, String title, int rewardPoints) {
		return insertMission(placeId, "OX", title, rewardPoints, null, true);
	}

	private UUID insertMission(UUID placeId, String type, String title, int rewardPoints, Integer maxAttempts,
			Boolean oxCorrectAnswer) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO missions (id, place_id, location, type, title, description, reward_points, "
						+ "max_attempts, ox_correct_answer) VALUES (?, ?, (SELECT location FROM places WHERE id = ?), "
						+ "?::mission_type, ?, '설명', ?, ?, ?)",
				id, placeId, placeId, type, title, rewardPoints, maxAttempts, oxCorrectAnswer);
		return id;
	}

	private int memberBadgeCount(UUID badgeId) {
		return Objects.requireNonNull(
				jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE member_id = ? AND badge_id = ?",
						Integer.class, member.memberId(), badgeId));
	}

	private String badgeName(UUID badgeId) {
		return jdbcTemplate.queryForObject("SELECT name FROM badges WHERE id = ?", String.class, badgeId);
	}

	private long notificationCount(UUID badgeId) {
		return Objects.requireNonNull(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? "
						+ "AND type = 'BADGE' AND target_type = 'BADGE' AND target_id = ?",
				Long.class, member.memberId(), badgeId));
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
