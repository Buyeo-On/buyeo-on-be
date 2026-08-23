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
class FootprintIntegrationTests {

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
		jdbcTemplate.update("DELETE FROM member_badges");
		jdbcTemplate.update("DELETE FROM badges");
		jdbcTemplate.update("DELETE FROM point_settlements");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM visit_records");
		jdbcTemplate.update("DELETE FROM missions");
		jdbcTemplate.update("DELETE FROM places");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 정산 완료 여행은 방문 장소, 통계, 포인트, 배지, 사진을 조합한 응답을 반환한다. */
	@Test
	@DisplayName("정산 완료된 여행은 방문 장소·통계·포인트·배지·사진을 포함한 발자취를 반환한다")
	void settledTripReturnsFullFootprint() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS);
		Instant endedAt = startedAt.plus(50, ChronoUnit.MINUTES);
		UUID tripId = insertTrip(member.memberId(), "SETTLED", startedAt, endedAt);
		UUID placeId = insertPlace("정림사지");
		UUID missionId = insertMission(placeId, "미션 A");
		insertSavedPlace(member.memberId(), placeId);
		insertVisitRecord(tripId, missionId, placeId);
		insertPointTransaction(member.memberId(), tripId, 100);
		UUID badgeId = insertBadge("첫 발걸음");
		insertMemberBadge(member.memberId(), badgeId, tripId);
		insertMissionPhoto(member.memberId(), tripId, missionId);

		when(privateImageGetUrlService.create(anyString())).thenReturn("https://signed-url.example.com/photo");

		performGet(member, tripId).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.trip.tripId").value(tripId.toString()))
				.andExpect(jsonPath("$.data.trip.status").value("SETTLED"))
				.andExpect(jsonPath("$.data.statistics.visitedPlaceCount").value(1))
				.andExpect(jsonPath("$.data.statistics.durationMinutes").value(50))
				.andExpect(jsonPath("$.data.visits.length()").value(1))
				.andExpect(jsonPath("$.data.visits[0].missionId").value(missionId.toString()))
				.andExpect(jsonPath("$.data.visits[0].place.placeId").value(placeId.toString()))
				.andExpect(jsonPath("$.data.visits[0].place.name").value("정림사지"))
				.andExpect(jsonPath("$.data.visits[0].place.saved").value(true))
				.andExpect(jsonPath("$.data.visits[0].place.distanceMeters").isEmpty())
				.andExpect(jsonPath("$.data.visits[0].place.walkingMinutes").isEmpty())
				.andExpect(jsonPath("$.data.points.balance").value(100))
				.andExpect(jsonPath("$.data.badges.length()").value(1))
				.andExpect(jsonPath("$.data.badges[0].badgeId").value(badgeId.toString()))
				.andExpect(jsonPath("$.data.badges[0].name").value("첫 발걸음"))
				.andExpect(jsonPath("$.data.badges[0].condition").value("조건"))
				.andExpect(jsonPath("$.data.badges[0].earnedAt").exists())
				.andExpect(jsonPath("$.data.photos.length()").value(1))
				.andExpect(jsonPath("$.data.photos[0].photoId").exists())
				.andExpect(jsonPath("$.data.photos[0].uploadedAt").exists())
				.andExpect(jsonPath("$.data.photos[0].url").value("https://signed-url.example.com/photo"));
	}

	/** IN_PROGRESS 상태의 여행을 조회하면 409를 반환한다. */
	@Test
	@DisplayName("IN_PROGRESS 상태 여행은 409를 반환한다")
	void inProgressTripReturnsConflict() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID tripId = insertTrip(member.memberId(), "IN_PROGRESS", Instant.now(), null);

		performGet(member, tripId).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** ENDED(정산 전) 상태의 여행을 조회하면 409를 반환한다. */
	@Test
	@DisplayName("ENDED 상태 여행은 409를 반환한다")
	void endedTripReturnsConflict() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS);
		UUID tripId = insertTrip(member.memberId(), "ENDED", startedAt, startedAt.plus(30, ChronoUnit.MINUTES));

		performGet(member, tripId).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
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
		Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS);
		UUID tripId = insertTrip(owner.memberId(), "SETTLED", startedAt, startedAt.plus(10, ChronoUnit.MINUTES));

		performGet(requester, tripId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 인증되지 않은 요청은 401을 반환한다. */
	@Test
	@DisplayName("인증되지 않은 요청은 401을 반환한다")
	void unauthenticatedRequestReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/trips/" + UUID.randomUUID() + "/footprint")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 방문 기록, 배지, 사진이 없으면 각각 빈 배열을 반환한다. */
	@Test
	@DisplayName("방문·배지·사진이 없으면 빈 배열을 반환한다")
	void emptyCollectionsReturnEmptyArrays() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS);
		UUID tripId = insertTrip(member.memberId(), "SETTLED", startedAt, startedAt.plus(20, ChronoUnit.MINUTES));

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.visits").isArray())
				.andExpect(jsonPath("$.data.visits.length()").value(0)).andExpect(jsonPath("$.data.badges").isArray())
				.andExpect(jsonPath("$.data.badges.length()").value(0)).andExpect(jsonPath("$.data.photos").isArray())
				.andExpect(jsonPath("$.data.photos.length()").value(0));
	}

	/** 다른 여행에서 획득한 배지는 포함하지 않는다. */
	@Test
	@DisplayName("다른 여행에서 획득한 배지는 포함하지 않는다")
	void badgesFromOtherTripAreExcluded() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(3, ChronoUnit.HOURS);
		UUID otherTripId = insertTrip(member.memberId(), "SETTLED", startedAt, startedAt.plus(20, ChronoUnit.MINUTES));
		UUID tripId = insertTrip(member.memberId(), "SETTLED", startedAt.plus(1, ChronoUnit.HOURS),
				startedAt.plus(90, ChronoUnit.MINUTES));
		UUID badgeId = insertBadge("다른 여행 배지");
		insertMemberBadge(member.memberId(), badgeId, otherTripId);

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.badges.length()").value(0));
	}

	/** 사진 presigned URL은 요청자 소유 여행의 사진에 대해서만 발급하며 타 회원 사진은 새지 않는다. */
	@Test
	@DisplayName("사진 presigned URL은 요청자 소유 여행의 사진에만 발급한다")
	void photoPresignedUrlOnlyForOwnedTripPhotos() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS);
		UUID tripId = insertTrip(member.memberId(), "SETTLED", startedAt, startedAt.plus(20, ChronoUnit.MINUTES));
		UUID placeId = insertPlace("장소");
		UUID missionId = insertMission(placeId, "미션");
		UUID myPhotoId = insertMissionPhoto(member.memberId(), tripId, missionId);

		AuthenticatedMember otherMember = insertAuthenticatedMember();
		UUID otherTripId = insertTrip(otherMember.memberId(), "SETTLED", startedAt,
				startedAt.plus(20, ChronoUnit.MINUTES));
		insertMissionPhoto(otherMember.memberId(), otherTripId, missionId);

		when(privateImageGetUrlService.create(anyString())).thenReturn("https://signed-url.example.com/only-mine");

		performGet(member, tripId).andExpect(status().isOk()).andExpect(jsonPath("$.data.photos.length()").value(1))
				.andExpect(jsonPath("$.data.photos[0].photoId").value(myPhotoId.toString()))
				.andExpect(jsonPath("$.data.photos[0].url").value("https://signed-url.example.com/only-mine"));
	}

	private ResultActions performGet(AuthenticatedMember member, UUID tripId) throws Exception {
		return mockMvc.perform(
				get("/trips/" + tripId + "/footprint").header("Authorization", "Bearer " + member.accessToken()));
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

	private void insertSavedPlace(UUID memberId, UUID placeId) {
		jdbcTemplate.update("INSERT INTO saved_places (member_id, place_id) VALUES (?, ?)", memberId, placeId);
	}

	private void insertVisitRecord(UUID tripId, UUID missionId, UUID placeId) {
		jdbcTemplate.update("INSERT INTO visit_records (id, trip_id, mission_id, place_id) VALUES (?, ?, ?, ?)",
				UUID.randomUUID(), tripId, missionId, placeId);
	}

	private void insertPointTransaction(UUID memberId, UUID tripId, long amount) {
		jdbcTemplate.update(
				"INSERT INTO point_transactions (id, member_id, trip_id, type, amount, description) "
						+ "VALUES (?, ?, ?, 'EARN'::point_transaction_type, ?, '적립')",
				UUID.randomUUID(), memberId, tripId, amount);
	}

	private UUID insertBadge(String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO badges (id, category, name, description, condition_text) "
				+ "VALUES (?, 'EXPLORATION'::badge_category, ?, '설명', '조건')", id, name);
		return id;
	}

	private void insertMemberBadge(UUID memberId, UUID badgeId, UUID tripId) {
		jdbcTemplate.update("INSERT INTO member_badges (member_id, badge_id, trip_id) VALUES (?, ?, ?)", memberId,
				badgeId, tripId);
	}

	private UUID insertMissionPhoto(UUID memberId, UUID tripId, UUID missionId) {
		UUID photoId = UUID.randomUUID();
		String objectKey = "private/missions/" + tripId + "/" + missionId + "/" + photoId;
		jdbcTemplate.update(
				"INSERT INTO mission_photos (id, member_id, trip_id, mission_id, object_key, content_type, "
						+ "file_size_bytes) VALUES (?, ?, ?, ?, ?, 'image/jpeg', 1024)",
				photoId, memberId, tripId, missionId, objectKey);
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
