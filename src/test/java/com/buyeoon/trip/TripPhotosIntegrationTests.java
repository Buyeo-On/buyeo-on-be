package com.buyeoon.trip;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.common.storage.PrivateImageGetUrlService;
import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TripPhotosIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

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
	private PrivateImageGetUrlService privateImageGetUrlService;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM mission_photos");
		jdbcTemplate.update("DELETE FROM missions");
		jdbcTemplate.update("DELETE FROM places");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 발자취와 달리 진행 중인 여행에서도 사진을 조회할 수 있다. */
	@Test
	@DisplayName("IN_PROGRESS 상태 여행도 사진을 조회할 수 있다")
	void inProgressTripReturnsPhotos() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);
		UUID placeId = insertPlace("정림사지");
		UUID missionId = insertMission(placeId, "미션 A");
		UUID photoId = insertMissionPhoto(member.memberId(), tripId, missionId);

		when(privateImageGetUrlService.create(anyString())).thenReturn("https://signed-url.example.com/photo");

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].photoId").value(photoId.toString()))
				.andExpect(jsonPath("$.data.items[0].url").value("https://signed-url.example.com/photo"))
				.andExpect(jsonPath("$.data.items[0].uploadedAt").exists())
				.andExpect(jsonPath("$.data.items[0].placeName").value("정림사지"));
	}

	/** 사진이 찍힌 미션이 연결된 장소명을 사진마다 정확히 매칭해 반환한다. */
	@Test
	@DisplayName("사진마다 촬영 미션이 연결된 장소명을 반환한다")
	void photosIncludeConnectedPlaceName() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);
		UUID placeA = insertPlace("정림사지");
		UUID placeB = insertPlace("궁남지");
		UUID missionA = insertMission(placeA, "미션 A");
		UUID missionB = insertMission(placeB, "미션 B");
		UUID photoAtPlaceA = insertMissionPhoto(member.memberId(), tripId, missionA, Instant.now().minus(1, ChronoUnit.HOURS));
		UUID photoAtPlaceB = insertMissionPhoto(member.memberId(), tripId, missionB, Instant.now());

		when(privateImageGetUrlService.create(anyString())).thenReturn("https://signed-url.example.com/photo");

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[0].photoId").value(photoAtPlaceA.toString()))
				.andExpect(jsonPath("$.data.items[0].placeName").value("정림사지"))
				.andExpect(jsonPath("$.data.items[1].photoId").value(photoAtPlaceB.toString()))
				.andExpect(jsonPath("$.data.items[1].placeName").value("궁남지"));
	}

	/** ENDED(정산 전) 상태 여행도 사진을 조회할 수 있다. */
	@Test
	@DisplayName("ENDED 상태 여행도 사진을 조회할 수 있다")
	void endedTripReturnsPhotos() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS);
		UUID tripId = insertTrip(member.memberId(), "ENDED", startedAt, startedAt.plus(30, ChronoUnit.MINUTES));
		UUID placeId = insertPlace("궁남지");
		UUID missionId = insertMission(placeId, "미션 A");
		insertMissionPhoto(member.memberId(), tripId, missionId);

		when(privateImageGetUrlService.create(anyString())).thenReturn("https://signed-url.example.com/photo");

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
	}

	/** SETTLED 상태 여행도 사진을 조회할 수 있다. */
	@Test
	@DisplayName("SETTLED 상태 여행도 사진을 조회할 수 있다")
	void settledTripReturnsPhotos() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS);
		UUID tripId = insertTrip(member.memberId(), "SETTLED", startedAt, startedAt.plus(50, ChronoUnit.MINUTES));
		UUID placeId = insertPlace("부소산성");
		UUID missionId = insertMission(placeId, "미션 A");
		insertMissionPhoto(member.memberId(), tripId, missionId);

		when(privateImageGetUrlService.create(anyString())).thenReturn("https://signed-url.example.com/photo");

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
	}

	/** 사진이 없으면 빈 배열을 반환한다. */
	@Test
	@DisplayName("사진이 없으면 빈 배열을 반환한다")
	void noPhotosReturnsEmptyArray() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray())
				.andExpect(jsonPath("$.data.items.length()").value(0));
	}

	/** 업로드 시각 오름차순으로 정렬한다. */
	@Test
	@DisplayName("업로드 시각 오름차순으로 반환한다")
	void photosOrderedByUploadedAtAscending() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);
		UUID placeId = insertPlace("정림사지");
		UUID missionId = insertMission(placeId, "미션 A");
		UUID olderPhotoId = insertMissionPhoto(member.memberId(), tripId, missionId,
				Instant.now().minus(1, ChronoUnit.HOURS));
		UUID newerPhotoId = insertMissionPhoto(member.memberId(), tripId, missionId, Instant.now());

		when(privateImageGetUrlService.create(anyString())).thenReturn("https://signed-url.example.com/photo");

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[0].photoId").value(olderPhotoId.toString()))
				.andExpect(jsonPath("$.data.items[1].photoId").value(newerPhotoId.toString()));
	}

	/** presigned URL은 요청자 소유 여행의 사진에 대해서만 발급하며 타 회원 사진은 새지 않는다. */
	@Test
	@DisplayName("presigned URL은 요청자 소유 여행의 사진에만 발급한다")
	void photosOnlyForOwnedTrip() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);
		UUID placeId = insertPlace("장소");
		UUID missionId = insertMission(placeId, "미션");
		UUID myPhotoId = insertMissionPhoto(member.memberId(), tripId, missionId);

		AuthenticatedMember otherMember = insertAuthenticatedMember();
		UUID otherTripId = insertTrip(otherMember.memberId(), "IN_PROGRESS", Instant.now(), null);
		insertMissionPhoto(otherMember.memberId(), otherTripId, missionId);

		when(privateImageGetUrlService.create(anyString())).thenReturn("https://signed-url.example.com/only-mine");

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].photoId").value(myPhotoId.toString()));
	}

	/** 존재하지 않는 tripId로 조회하면 404를 반환한다. */
	@Test
	@DisplayName("존재하지 않는 tripId는 404를 반환한다")
	void nonExistentTripReturnsNotFound() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		performGet(member, UUID.randomUUID()).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 다른 회원 소유의 tripId로 조회하면 404를 반환한다. */
	@Test
	@DisplayName("타 회원 소유 tripId는 404를 반환한다")
	void otherMembersTripReturnsNotFound() throws Exception {
		AuthenticatedMember owner = insertAuthenticatedMember();
		AuthenticatedMember requester = insertAuthenticatedMember();
		UUID tripId = insertTrip(owner.memberId(), "IN_PROGRESS", Instant.now(), null);

		performGet(requester, tripId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 인증되지 않은 요청은 401을 반환한다. */
	@Test
	@DisplayName("인증되지 않은 요청은 401을 반환한다")
	void unauthenticatedRequestReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/trips/" + UUID.randomUUID() + "/photos")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private ResultActions performGet(AuthenticatedMember member, UUID tripId) throws Exception {
		return mockMvc
				.perform(get("/trips/" + tripId + "/photos").header("Authorization", "Bearer " + member.accessToken()));
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

	private UUID insertTrip(UUID memberId, String status, Instant startedAt, Instant endedAt) {
		UUID tripId = UUID.randomUUID();
		Instant settledAt = "SETTLED".equals(status) ? endedAt : null;
		jdbcTemplate.update(
				"INSERT INTO trips (id, member_id, status, started_at, ended_at, settled_at) "
						+ "VALUES (?, ?, ?::trip_status, ?, ?, ?)",
				tripId, memberId, status, Timestamp.from(startedAt == null ? Instant.now() : startedAt),
				endedAt == null ? null : Timestamp.from(endedAt), settledAt == null ? null : Timestamp.from(settledAt));
		return tripId;
	}

	private UUID insertPlace(String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate
				.update("INSERT INTO places (id, category, name, location) VALUES (?, 'HERITAGE'::place_category, ?, "
						+ "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)", id, name, 126.9, 36.2);
		return id;
	}

	private UUID insertMission(UUID placeId, String title) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO missions (id, place_id, location, type, title, description, reward_points, "
						+ "ox_correct_answer) VALUES (?, ?, (SELECT location FROM places WHERE id = ?), "
						+ "'OX'::mission_type, ?, '설명', 10, true)",
				id, placeId, placeId, title);
		return id;
	}

	private UUID insertMissionPhoto(UUID memberId, UUID tripId, UUID missionId) {
		return insertMissionPhoto(memberId, tripId, missionId, Instant.now());
	}

	private UUID insertMissionPhoto(UUID memberId, UUID tripId, UUID missionId, Instant uploadedAt) {
		UUID photoId = UUID.randomUUID();
		String objectKey = "private/missions/" + tripId + "/" + missionId + "/" + photoId;
		jdbcTemplate.update(
				"INSERT INTO mission_photos (id, member_id, trip_id, mission_id, object_key, content_type, "
						+ "file_size_bytes, uploaded_at) VALUES (?, ?, ?, ?, ?, 'image/jpeg', 1024, ?)",
				photoId, memberId, tripId, missionId, objectKey, Timestamp.from(uploadedAt));
		return photoId;
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
