package com.buyeoon.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.common.storage.MissionPhotoObjectStore;
import com.buyeoon.common.storage.MissionPhotoObjectStore.MissionPhotoObject;
import com.buyeoon.common.storage.MissionPhotoUploadPresigner;
import com.buyeoon.common.storage.MissionPhotoUploadPresigner.MissionPhotoUploadTarget;
import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.mission.application.MissionCompleted;
import com.buyeoon.trip.VisitRecorded;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** UC-09 사진 인증 업로드 URL 발급과 사진 제출을 검증한다. S3 연동은 테스트 대역으로 대체한다. */
@SpringBootTest(properties = "storage.images.max-upload-bytes=1000000")
@AutoConfigureMockMvc
@RecordApplicationEvents
@Testcontainers
class MissionPhotoSubmissionIntegrationTests {

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
	private ApplicationEvents applicationEvents;

	@MockitoBean
	private MissionPhotoUploadPresigner photoUploadPresigner;

	@MockitoBean
	private MissionPhotoObjectStore photoObjectStore;

	private AuthenticatedMember member;

	@BeforeEach
	void setUpMember() {
		member = insertAuthenticatedMember();
		when(photoUploadPresigner.presign(anyString(), any(), anyString(), anyLong()))
				.thenReturn(new MissionPhotoUploadTarget("https://example-bucket.s3.amazonaws.com/upload",
						Map.of("Content-Type", "image/jpeg"), Instant.now().plusSeconds(600)));
	}

	@AfterEach
	void cleanUp() {
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
	 * 소유 트립·PHOTO 유형 미션이면 photoId, uploadUrl, method, headers, successStatus,
	 * expiresAt을 201로 받는다.
	 */
	@Test
	@DisplayName("사진 미션 발급 요청은 201과 업로드 대상을 받고 mission_photos row를 만들지 않는다")
	void issuesUploadUrlWithoutCreatingMissionPhotoRow() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);

		mockMvc.perform(presignRequest(tripId, missionId, "issue-key")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.photoId").isNotEmpty())
				.andExpect(jsonPath("$.data.uploadUrl").value("https://example-bucket.s3.amazonaws.com/upload"))
				.andExpect(jsonPath("$.data.method").value("PUT"))
				.andExpect(jsonPath("$.data.headers.Content-Type").value("image/jpeg"))
				.andExpect(jsonPath("$.data.successStatus").value(200))
				.andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mission_photos", Integer.class)).isZero();
	}

	/** PHOTO가 아닌 미션에 대한 발급 요청은 400을 받는다. */
	@Test
	@DisplayName("PHOTO가 아닌 미션에 발급을 요청하면 400을 받는다")
	void returns400WhenMissionIsNotPhotoType() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("OX 장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(presignRequest(tripId, missionId, "not-photo-key")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** 존재하지 않거나 본인 소유가 아닌 여행은 404를 받는다. */
	@Test
	@DisplayName("본인 소유가 아니거나 존재하지 않는 여행으로 발급을 요청하면 404를 받는다")
	void returns404WhenTripIsNotOwnedOrMissing() throws Exception {
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);

		mockMvc.perform(presignRequest(UUID.randomUUID(), missionId, "missing-trip-key"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 진행 중이 아닌 본인 여행은 409를 받는다. */
	@Test
	@DisplayName("진행 중이 아닌 본인 여행으로 발급을 요청하면 409를 받는다")
	void returns409WhenOwnTripIsNotInProgress() throws Exception {
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		UUID endedTripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status, ended_at) VALUES (?, ?, 'ENDED', now())",
				endedTripId, member.memberId());

		mockMvc.perform(presignRequest(endedTripId, missionId, "ended-trip-key")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("TRIP_NOT_IN_PROGRESS"));
	}

	/** 서버에 설정된 최대 업로드 크기를 초과하면 413을 받는다. */
	@Test
	@DisplayName("설정된 최대 크기를 초과하면 413을 받는다")
	void returns413WhenFileSizeExceedsConfiguredMax() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);

		mockMvc.perform(presignRequestBody(tripId, missionId, "too-large-key",
				fileSizeRequestJson(tripId, missionId, 2_000_000))).andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.data.code").value("PAYLOAD_TOO_LARGE"));
	}

	/** 같은 키와 같은 본문의 재요청은 최초 응답을 그대로 재사용한다. */
	@Test
	@DisplayName("발급 요청도 동일한 멱등성 키·본문 재요청은 최초 응답을 재사용한다")
	void presignReplaysFirstResponseForSameIdempotentRequest() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);

		String first = mockMvc.perform(presignRequest(tripId, missionId, "replay-key")).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String retried = mockMvc.perform(presignRequest(tripId, missionId, "replay-key"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		assertThat(retried).isEqualTo(first);
	}

	/** 같은 키를 다른 본문에 재사용하면 409를 받는다. */
	@Test
	@DisplayName("발급 요청도 멱등성 키를 다른 본문에 재사용하면 409를 받는다")
	void presignReusedKeyWithDifferentBodyConflicts() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID otherPlace = insertProjectedPlace("다른 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		UUID otherMissionId = insertPhotoMission(otherPlace, "다른 사진 미션", 150);

		mockMvc.perform(presignRequest(tripId, missionId, "conflict-key")).andExpect(status().isCreated());
		mockMvc.perform(presignRequest(tripId, otherMissionId, "conflict-key")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	/** 인증되지 않은 발급 요청은 401을 받는다. */
	@Test
	@DisplayName("인증하지 않고 발급을 요청하면 401을 받는다")
	void returns401WhenPresignRequestIsUnauthenticated() throws Exception {
		mockMvc.perform(post("/mission-photos/presigned-url").header("Idempotency-Key", "unauth-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content(fileSizeRequestJson(UUID.randomUUID(), UUID.randomUUID(), 100)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 소유자·크기·Content-Type이 모두 일치하는 사진 제출은 판정 없이 완료 처리되고 보상·방문 기록을 받는다. */
	@Test
	@DisplayName("검증에 성공한 사진 제출은 완료 처리되고 보상·방문 기록을 받는다")
	void verifiedPhotoSubmissionCompletesMissionAndGrantsRewardAndVisit() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		UUID photoId = UUID.randomUUID();
		stubMatchingPhoto(tripId, missionId, photoId, member.memberId(), "image/jpeg", 1024);

		mockMvc.perform(submit(missionId, "photo-complete", photoRequest(tripId, photoId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.completed").value(true))
				.andExpect(jsonPath("$.data.rewardPoints").value(150))
				.andExpect(jsonPath("$.data.visitRecorded").value(true));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM mission_participations WHERE trip_id = ? AND mission_id = ?", String.class, tripId,
				missionId)).isEqualTo("COMPLETED");
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM mission_photos WHERE trip_id = ? AND mission_id = ?",
						Integer.class, tripId, missionId))
				.isEqualTo(1);
		assertThat(applicationEvents.stream(MissionCompleted.class)).hasSize(1);
		assertThat(applicationEvents.stream(VisitRecorded.class)).hasSize(1);
	}

	/** 존재하지 않는 photoId로 제출하면 404를 받는다. */
	@Test
	@DisplayName("존재하지 않는 photoId로 제출하면 404를 받는다")
	void returns404WhenPhotoObjectDoesNotExist() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		when(photoObjectStore.head(anyString())).thenReturn(Optional.empty());

		mockMvc.perform(submit(missionId, "photo-missing", photoRequest(tripId, UUID.randomUUID())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mission_participations WHERE trip_id = ?",
				Integer.class, tripId)).isZero();
	}

	/** 다른 회원이 발급받은 photoId로 제출하면 404를 받는다. */
	@Test
	@DisplayName("다른 회원의 photoId로 제출하면 404를 받는다")
	void returns404WhenPhotoOwnedByAnotherMember() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		UUID photoId = UUID.randomUUID();
		stubMatchingPhoto(tripId, missionId, photoId, UUID.randomUUID(), "image/jpeg", 1024);

		mockMvc.perform(submit(missionId, "photo-other-owner", photoRequest(tripId, photoId)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 실제 크기가 발급 요청과 다르면 400을 받고 상태를 바꾸지 않는다. */
	@Test
	@DisplayName("실제 크기가 발급 요청과 다르면 400을 받고 상태를 바꾸지 않는다")
	void returns400WhenFileSizeMismatches() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		UUID photoId = UUID.randomUUID();
		when(photoObjectStore.head(anyString())).thenReturn(
				Optional.of(new MissionPhotoObject(member.memberId(), "image/jpeg", 2048, "image/jpeg", 1024)));

		mockMvc.perform(submit(missionId, "photo-size-mismatch", photoRequest(tripId, photoId)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mission_photos WHERE trip_id = ?", Integer.class,
				tripId)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mission_participations WHERE trip_id = ?",
				Integer.class, tripId)).isZero();
	}

	/** Content-Type이 발급 요청과 다르면 400을 받는다. */
	@Test
	@DisplayName("Content-Type이 발급 요청과 다르면 400을 받는다")
	void returns400WhenContentTypeMismatches() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		UUID photoId = UUID.randomUUID();
		when(photoObjectStore.head(anyString())).thenReturn(
				Optional.of(new MissionPhotoObject(member.memberId(), "image/png", 1024, "image/jpeg", 1024)));

		mockMvc.perform(submit(missionId, "photo-type-mismatch", photoRequest(tripId, photoId)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** 이미 완료된 사진 미션에 같은 photoId로 다시 제출하면 409를 받는다. */
	@Test
	@DisplayName("완료된 사진 미션에 재제출하면 409를 받는다")
	void resubmittingCompletedPhotoMissionReturns409() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		UUID photoId = UUID.randomUUID();
		stubMatchingPhoto(tripId, missionId, photoId, member.memberId(), "image/jpeg", 1024);
		mockMvc.perform(submit(missionId, "photo-first", photoRequest(tripId, photoId))).andExpect(status().isOk());

		mockMvc.perform(submit(missionId, "photo-second", photoRequest(tripId, photoId)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** 반올림 전 실제 거리가 100m를 초과하면 403을 받고 상태를 바꾸지 않는다. */
	@Test
	@DisplayName("100m를 초과한 사진 제출은 403을 받고 상태를 바꾸지 않는다")
	void returns403WhenBeyondParticipationRadius() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("먼 사진 장소", 100.1);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		UUID photoId = UUID.randomUUID();
		stubMatchingPhoto(tripId, missionId, photoId, member.memberId(), "image/jpeg", 1024);

		mockMvc.perform(submit(missionId, "photo-too-far", photoRequest(tripId, photoId)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("LOCATION_VERIFICATION_FAILED"));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mission_participations WHERE trip_id = ?",
				Integer.class, tripId)).isZero();
	}

	/** 같은 키와 같은 본문의 제출 재요청은 최초 응답을 재사용하고 부작용을 반복하지 않는다. */
	@Test
	@DisplayName("사진 제출도 동일한 멱등성 요청은 최초 응답을 재사용한다")
	void sameIdempotentPhotoSubmissionReplaysFirstResponse() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);
		UUID photoId = UUID.randomUUID();
		stubMatchingPhoto(tripId, missionId, photoId, member.memberId(), "image/jpeg", 1024);
		String body = photoRequest(tripId, photoId);

		String first = mockMvc.perform(submit(missionId, "photo-retry", body)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String retried = mockMvc.perform(submit(missionId, "photo-retry", body)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

		assertThat(retried).isEqualTo(first);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mission_photos WHERE trip_id = ?", Integer.class,
				tripId)).isEqualTo(1);
	}

	/** 같은 문화재의 다른 사진 미션을 완료해도 방문 기록은 한 번만 생성된다. */
	@Test
	@DisplayName("같은 문화재의 다른 사진 미션을 완료해도 방문 기록은 한 번만 생성된다")
	void completingAnotherPhotoMissionForSamePlaceDoesNotDuplicateVisit() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("공통 장소", 50);
		UUID firstMission = insertPhotoMission(place, "사진 미션1", 100);
		UUID secondMission = insertPhotoMission(place, "사진 미션2", 100);
		UUID firstPhotoId = UUID.randomUUID();
		UUID secondPhotoId = UUID.randomUUID();
		stubMatchingPhoto(tripId, firstMission, firstPhotoId, member.memberId(), "image/jpeg", 1024);
		stubMatchingPhoto(tripId, secondMission, secondPhotoId, member.memberId(), "image/jpeg", 1024);

		mockMvc.perform(submit(firstMission, "visit-first", photoRequest(tripId, firstPhotoId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.visitRecorded").value(true));
		mockMvc.perform(submit(secondMission, "visit-second", photoRequest(tripId, secondPhotoId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.visitRecorded").value(false));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM visit_records WHERE trip_id = ? AND place_id = ?",
				Integer.class, tripId, place)).isEqualTo(1);
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

	private String locationJson() {
		return "{\"latitude\":" + ORIGIN_LATITUDE + ",\"longitude\":" + ORIGIN_LONGITUDE
				+ ",\"accuracyMeters\":5.5,\"capturedAt\":\"2026-08-12T15:30:00+09:00\"}";
	}

	private MockHttpServletRequestBuilder presignRequest(UUID tripId, UUID missionId, String idempotencyKey) {
		return presignRequestBody(tripId, missionId, idempotencyKey, fileSizeRequestJson(tripId, missionId, 1024));
	}

	private MockHttpServletRequestBuilder presignRequestBody(UUID tripId, UUID missionId, String idempotencyKey,
			String body) {
		return post("/mission-photos/presigned-url").header("Authorization", "Bearer " + member.accessToken())
				.header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String fileSizeRequestJson(UUID tripId, UUID missionId, long fileSizeBytes) {
		return "{\"tripId\":\"" + tripId + "\",\"missionId\":\"" + missionId
				+ "\",\"fileName\":\"photo.jpg\",\"contentType\":\"image/jpeg\",\"fileSizeBytes\":" + fileSizeBytes
				+ "}";
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
		return insertMission(placeId, "OX", title, rewardPoints, maxAttempts, correctAnswer);
	}

	private UUID insertPhotoMission(UUID placeId, String title, int rewardPoints) {
		return insertMission(placeId, "PHOTO", title, rewardPoints, null, null);
	}

	private UUID insertMission(UUID placeId, String type, String title, int rewardPoints, Integer maxAttempts,
			Boolean oxCorrectAnswer) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO missions (id, place_id, type, title, description, reward_points, max_attempts, "
						+ "ox_correct_answer) VALUES (?, ?, ?::mission_type, ?, '설명', ?, ?, ?)",
				id, placeId, type, title, rewardPoints, maxAttempts, oxCorrectAnswer);
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
	}

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
