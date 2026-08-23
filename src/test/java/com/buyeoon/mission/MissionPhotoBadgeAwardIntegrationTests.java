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
 * #186 인증 사진 15회 제출로 '추억 수집가' 배지를 획득하는 흐름의 통합 테스트다. V17·V18 migration이 시딩한 실제
 * catalog 배지({@code 30000000-0000-4000-8000-000000000005})를 그대로 사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MissionPhotoBadgeAwardIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	private static final UUID MEMORY_COLLECTOR_BADGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000005");

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
		// V17·V18이 시딩한 배지 catalog는 다른 테스트 메서드도 재사용하므로 badges/badge_conditions는
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

	/** 인증 사진을 15회 제출하면 그 순간 '추억 수집가' 배지를 획득한다. */
	@Test
	@DisplayName("인증 사진 15회 제출을 달성하면 '추억 수집가' 배지를 획득한다")
	void fifteenthPhotoSubmissionAwardsMemoryCollectorBadge() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);

		for (int i = 1; i < 15; i++) {
			submitPhotoMission(tripId, place, i).andExpect(status().isOk())
					.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));
		}

		submitPhotoMission(tripId, place, 15).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(MEMORY_COLLECTOR_BADGE_ID.toString()))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].name").value("추억 수집가"));

		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE member_id = ? AND badge_id = ?",
						Integer.class, member.memberId(), MEMORY_COLLECTOR_BADGE_ID))
				.isEqualTo(1);
	}

	/** 객관식·OX 제출은 인증 사진 제출 수에 포함되지 않는다. */
	@Test
	@DisplayName("PHOTO가 아닌 제출은 인증 사진 집계에 포함되지 않는다")
	void nonPhotoSubmissionsDoNotCountTowardPhotoMetric() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("퀴즈 장소", 50);

		for (int i = 1; i <= 15; i++) {
			UUID missionId = insertOxMission(place, "OX 미션 " + i, 10);
			// OX 정답 제출은 '백제 박사'·'무결점'·'집중력' 등 퀴즈 배지를 정당하게 지급할 수 있으므로,
			// 새로 지급된 배지가 '추억 수집가'(인증 사진 배지)가 아닌지로 좁혀 검증한다.
			mockMvc.perform(submit(missionId, "ox-submit-" + i, oxRequest(tripId))).andExpect(status().isOk())
					.andExpect(jsonPath("$.data.newlyAwardedBadges[?(@.badgeId == '" + MEMORY_COLLECTOR_BADGE_ID + "')]",
							hasSize(0)));
		}

		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE member_id = ? AND badge_id = ?",
						Integer.class, member.memberId(), MEMORY_COLLECTOR_BADGE_ID))
				.isZero();
	}

	private ResultActions submitPhotoMission(UUID tripId, UUID place, int index) throws Exception {
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

	private String photoRequest(UUID tripId, UUID photoId) {
		return "{\"tripId\":\"" + tripId + "\",\"type\":\"PHOTO\",\"photoId\":\"" + photoId + "\",\"location\":"
				+ locationJson() + "}";
	}

	private String oxRequest(UUID tripId) {
		return "{\"tripId\":\"" + tripId + "\",\"type\":\"OX\",\"oxAnswer\":true,\"location\":" + locationJson() + "}";
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
