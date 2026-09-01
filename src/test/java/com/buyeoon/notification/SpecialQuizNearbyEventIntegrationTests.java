package com.buyeoon.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.mission.application.SpecialQuizExposureDecider;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 스페셜 퀴즈 근접 알림의 공개 seam인 {@code POST /notifications/missions/{missionId}/nearby-events}를
 * 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SpecialQuizNearbyEventIntegrationTests {

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

	@MockitoBean
	private SpecialQuizExposureDecider specialQuizExposureDecider;

	private AuthenticatedMember member;

	@BeforeEach
	void setUp() {
		member = insertAuthenticatedMember();
		when(specialQuizExposureDecider.isExposedToday(any(), any())).thenReturn(true);
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM mission_participations");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update(
				"DELETE FROM missions WHERE place_id IN (SELECT id FROM places WHERE ST_Y(location::geometry) < -70)");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 오늘 노출된 스페셜 퀴즈에 반경 이내로 접근하면 알림을 생성한다. */
	@Test
	@DisplayName("노출된 스페셜 퀴즈 반경 이내 진입은 알림을 생성한다")
	void withinRadiusExposedSpecialQuizCreatesNotification() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("스페셜 퀴즈 장소", 20);
		UUID missionId = insertOxMission(place, "스페셜 퀴즈", 100, 3, true);

		performNotify(missionId, "nearby-key-01", request(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.notificationSent").value(true));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'NEARBY_QUIZ' AND target_id = ?",
				Long.class, member.memberId(), missionId)).isEqualTo(1L);
	}

	/** 최대 도전 횟수가 없는 일반 미션은 스페셜 퀴즈가 아니므로 알림을 생성하지 않는다. */
	@Test
	@DisplayName("일반 미션은 알림을 생성하지 않는다")
	void regularMissionDoesNotCreateNotification() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("일반 미션 장소", 20);
		UUID missionId = insertOxMission(place, "일반 미션", 100, null, true);

		performNotify(missionId, "nearby-key-02", request(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertNoNearbyQuizNotification(missionId);
	}

	/** 오늘 노출 대상이 아닌 스페셜 퀴즈는 클라이언트가 요청해도 알림을 생성하지 않는다. */
	@Test
	@DisplayName("오늘 노출 대상이 아니면 알림을 생성하지 않는다")
	void notExposedTodaySkipsNotification() throws Exception {
		when(specialQuizExposureDecider.isExposedToday(any(), any())).thenReturn(false);
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("비노출 스페셜 퀴즈 장소", 20);
		UUID missionId = insertOxMission(place, "비노출 스페셜 퀴즈", 100, 3, true);

		performNotify(missionId, "nearby-key-03", request(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertNoNearbyQuizNotification(missionId);
	}

	/** 이미 완료·소진한 스페셜 퀴즈는 다시 알릴 이유가 없으므로 알림을 생성하지 않는다. */
	@Test
	@DisplayName("이미 참여한 스페셜 퀴즈는 알림을 생성하지 않는다")
	void alreadyParticipatedSkipsNotification() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("완료된 스페셜 퀴즈 장소", 20);
		UUID missionId = insertOxMission(place, "완료된 스페셜 퀴즈", 100, 3, true);
		insertParticipation(tripId, missionId, "COMPLETED");

		performNotify(missionId, "nearby-key-04", request(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertNoNearbyQuizNotification(missionId);
	}

	/** 서버가 좌표로 재계산한 거리가 참여 반경(30m)을 넘으면 클라이언트 신고를 신뢰하지 않고 알림을 생성하지 않는다. */
	@Test
	@DisplayName("참여 반경 밖이면 알림을 생성하지 않는다")
	void outsideParticipationRadiusSkipsNotification() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("먼 스페셜 퀴즈 장소", 40);
		UUID missionId = insertOxMission(place, "먼 스페셜 퀴즈", 100, 3, true);

		performNotify(missionId, "nearby-key-05", request(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertNoNearbyQuizNotification(missionId);
	}

	/** 같은 미션에 대해 마지막 발송 후 12시간이 지나지 않았으면 재발송하지 않는다. */
	@Test
	@DisplayName("쿨다운 12시간 이내에는 같은 미션을 재발송하지 않는다")
	void withinCooldownSkipsNotification() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("쿨다운 스페셜 퀴즈 장소", 20);
		UUID missionId = insertOxMission(place, "쿨다운 스페셜 퀴즈", 100, 3, true);
		insertNearbyQuizNotification(member.memberId(), missionId, Instant.now().minus(11, ChronoUnit.HOURS));

		performNotify(missionId, "nearby-key-06", request(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'NEARBY_QUIZ' AND target_id = ?",
				Long.class, member.memberId(), missionId)).isEqualTo(1L);
	}

	/** 다른 미션의 쿨다운은 서로 영향을 주지 않는다. */
	@Test
	@DisplayName("다른 미션의 쿨다운은 신규 알림을 막지 않는다")
	void cooldownIsScopedPerMission() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID otherPlace = insertProjectedPlace("다른 스페셜 퀴즈 장소", 15);
		UUID otherMissionId = insertOxMission(otherPlace, "다른 스페셜 퀴즈", 100, 3, true);
		insertNearbyQuizNotification(member.memberId(), otherMissionId, Instant.now().minus(1, ChronoUnit.HOURS));

		UUID place = insertProjectedPlace("새 스페셜 퀴즈 장소", 20);
		UUID missionId = insertOxMission(place, "새 스페셜 퀴즈", 100, 3, true);

		performNotify(missionId, "nearby-key-07", request(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(true));
	}

	/** 같은 키와 같은 본문의 재요청은 최초 처리 결과를 그대로 반환하며 알림을 다시 만들지 않는다. */
	@Test
	@DisplayName("동일한 멱등성 요청은 최초 응답을 재사용한다")
	void sameIdempotentRequestReturnsFirstResponse() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("멱등성 스페셜 퀴즈 장소", 20);
		UUID missionId = insertOxMission(place, "멱등성 스페셜 퀴즈", 100, 3, true);
		String key = "retry-nearby-key";

		String first = performNotify(missionId, key, request(tripId)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String retried = performNotify(missionId, key, request(tripId)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

		assertThat(retried).isEqualTo(first);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'NEARBY_QUIZ' AND target_id = ?",
				Long.class, member.memberId(), missionId)).isEqualTo(1L);
	}

	/** 존재하지 않는 미션은 404를 반환한다. */
	@Test
	@DisplayName("존재하지 않는 미션은 404를 반환한다")
	void unknownMissionReturnsNotFound() throws Exception {
		UUID tripId = startTrip(member.memberId());

		performNotify(UUID.randomUUID(), "nearby-key-08", request(tripId)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 인증되지 않은 요청은 알림 계층에 도달하지 않는다. */
	@Test
	@DisplayName("스페셜 퀴즈 근접 알림에는 유효한 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc
				.perform(post("/notifications/missions/" + UUID.randomUUID() + "/nearby-events")
						.header("Idempotency-Key", "unauth-nearby-key").contentType(MediaType.APPLICATION_JSON)
						.content(request(UUID.randomUUID())))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 알 수 없는 필드가 섞인 요청은 400 INVALID_REQUEST다. */
	@Test
	@DisplayName("잘못된 요청 형식은 400을 반환한다")
	void invalidRequestBodyIsRejected() throws Exception {
		performNotify(UUID.randomUUID(), "invalid-nearby-key",
				"{\"tripId\":\"" + UUID.randomUUID() + "\",\"location\":{\"latitude\":36.27}}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	private ResultActions performNotify(UUID missionId, String key, String body) throws Exception {
		var request = post("/notifications/missions/" + missionId + "/nearby-events")
				.header("Authorization", "Bearer " + member.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content(body);
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mockMvc.perform(request);
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
				"INSERT INTO missions (id, place_id, location, type, title, description, reward_points, "
						+ "max_attempts, ox_correct_answer) VALUES (?, ?, "
						+ "(SELECT location FROM places WHERE id = ?), 'OX'::mission_type, ?, '설명', ?, ?, ?)",
				id, placeId, placeId, title, rewardPoints, maxAttempts, correctAnswer);
		return id;
	}

	private void insertParticipation(UUID tripId, UUID missionId, String status) {
		Timestamp completedAt = "COMPLETED".equals(status) ? Timestamp.from(Instant.now()) : null;
		jdbcTemplate.update("""
				INSERT INTO mission_participations (id, trip_id, mission_id, status, attempt_count, completed_at)
				VALUES (?, ?, ?, ?::mission_status, 1, ?)
				""", UUID.randomUUID(), tripId, missionId, status, completedAt);
	}

	private void insertNearbyQuizNotification(UUID memberId, UUID missionId, Instant occurredAt) {
		jdbcTemplate.update("""
				INSERT INTO notifications (id, member_id, type, title, body, target_type, target_id, occurred_at)
				VALUES (?, ?, 'NEARBY_QUIZ', '근처에 스페셜 퀴즈가 있어요!', '지금 도전하면 특별한 보상을 받을 수 있어요.', 'MISSION', ?, ?)
				""", UUID.randomUUID(), memberId, missionId, Timestamp.from(occurredAt));
	}

	private void assertNoNearbyQuizNotification(UUID missionId) {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'NEARBY_QUIZ' AND target_id = ?",
				Long.class, member.memberId(), missionId)).isZero();
	}

	private String request(UUID tripId) {
		return "{\"tripId\":\"" + tripId + "\",\"location\":{\"latitude\":" + ORIGIN_LATITUDE + ",\"longitude\":"
				+ ORIGIN_LONGITUDE + ",\"accuracyMeters\":5.5,\"capturedAt\":\"2026-08-12T15:30:00+09:00\"}}";
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
