package com.buyeoon.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MissionControllerIntegrationTests {

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
	private org.springframework.test.web.servlet.MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private ObjectMapper objectMapper;

	private AuthenticatedMember member;

	@BeforeEach
	void setUpMember() {
		member = insertAuthenticatedMember();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update(
				"DELETE FROM missions WHERE place_id IN (SELECT id FROM places WHERE ST_Y(location::geometry) < -70)");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 진행 중인 여행으로 요청하면 500m 이내 OX·객관식·사진 인증 미션을 모두 받는다. */
	@Test
	@DisplayName("진행 중인 여행이 있으면 500m 이내 모든 유형의 미션을 200으로 받는다")
	void returns200WithAllMissionTypesWithin500m() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID placeA = insertPlace("장소1", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE);
		UUID placeB = insertPlace("장소2", ORIGIN_LATITUDE + 0.002, ORIGIN_LONGITUDE);
		UUID placeC = insertPlace("장소3", ORIGIN_LATITUDE + 0.003, ORIGIN_LONGITUDE);
		insertOxMission(placeA, "OX 미션", 100, null, true);
		insertMultipleChoiceMission(placeB, "객관식 미션", 100, null);
		insertPhotoMission(placeC, "사진 미션", 150);

		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.items.length()").value(3)).andExpect(jsonPath("$.data.items[*].type")
						.value(org.hamcrest.Matchers.containsInAnyOrder("OX", "MULTIPLE_CHOICE", "PHOTO")));
	}

	/** 500m 직전 미션은 포함되고 직후 미션은 제외된다. */
	@Test
	@DisplayName("500m 경계 안쪽 미션은 포함되고 바깥쪽 미션은 제외된다")
	void includesMissionJustInsideAndExcludesJustBeyond500m() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID insidePlace = insertProjectedPlace("경계 안쪽", 499.9);
		UUID outsidePlace = insertProjectedPlace("경계 바깥", 500.1);
		UUID insideMission = insertOxMission(insidePlace, "안쪽 미션", 100, null, true);
		insertOxMission(outsidePlace, "바깥 미션", 100, null, true);

		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].missionId").value(insideMission.toString()));
	}

	/** 실제 거리가 같으면 missionId 오름차순으로 안정 정렬된다. */
	@Test
	@DisplayName("같은 거리의 미션은 missionId 오름차순으로 정렬된다")
	void ordersByDistanceThenMissionIdWhenTied() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertPlace("공통 장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE);
		insertOxMission(place, "미션A", 100, null, true);
		insertOxMission(place, "미션B", 100, null, true);
		// Postgres uuid는 바이트 단위 비교라 java.util.UUID#compareTo(부호 있는 long 비교)와 순서가
		// 다를 수 있으므로 DB에서 실제 정렬 순서를 그대로 가져온다.
		List<UUID> expectedOrder = jdbcTemplate.queryForList("SELECT id FROM missions WHERE place_id = ? ORDER BY id",
				UUID.class, place);

		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[0].missionId").value(expectedOrder.get(0).toString()))
				.andExpect(jsonPath("$.data.items[1].missionId").value(expectedOrder.get(1).toString()));
	}

	/** 같은 장소에 연결된 여러 미션은 각각 반환된다. */
	@Test
	@DisplayName("같은 장소의 여러 미션은 각각 반환된다")
	void returnsEachMissionSeparatelyForSamePlace() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertPlace("문화재", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE);
		insertOxMission(place, "OX", 100, null, true);
		insertMultipleChoiceMission(place, "객관식", 100, null);

		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(2));
	}

	/** 500m 안에 미션이 없으면 빈 목록을 정상 반환한다. */
	@Test
	@DisplayName("주변에 미션이 없으면 빈 목록을 받는다")
	void returnsEmptyListWhenNoMissionsNearby() throws Exception {
		UUID tripId = startTrip(member.memberId());

		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray())
				.andExpect(jsonPath("$.data.items.length()").value(0));
	}

	/** 목록 항목은 OpenAPI Mission 스키마의 식별자, 유형, 제목, 장소명·좌표, 거리, 보상, 상태, 참여 반경을 포함한다. */
	@Test
	@DisplayName("목록 항목에 필요한 모든 필드가 포함된다")
	void includesAllRequiredFieldsInResponse() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("부소산성", 50);
		UUID missionId = insertOxMission(place, "OX 퀴즈", 100, null, true);

		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].missionId").value(missionId.toString()))
				.andExpect(jsonPath("$.data.items[0].tripId").value(tripId.toString()))
				.andExpect(jsonPath("$.data.items[0].placeId").value(place.toString()))
				.andExpect(jsonPath("$.data.items[0].placeName").value("부소산성"))
				.andExpect(jsonPath("$.data.items[0].latitude").exists())
				.andExpect(jsonPath("$.data.items[0].longitude").exists())
				.andExpect(jsonPath("$.data.items[0].distanceMeters").exists())
				.andExpect(jsonPath("$.data.items[0].type").value("OX"))
				.andExpect(jsonPath("$.data.items[0].title").value("OX 퀴즈"))
				.andExpect(jsonPath("$.data.items[0].rewardPoints").value(100))
				.andExpect(jsonPath("$.data.items[0].availability").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.items[0].participationRadiusMeters").value(100))
				.andExpect(jsonPath("$.data.items[0].remainingAttempts").isEmpty());
	}

	/** 참여 기록이 없는 미션은 100m 이내면 AVAILABLE, 초과면 LOCKED이며 100m 경계는 AVAILABLE이다. */
	@Test
	@DisplayName("참여 기록이 없으면 100m 이내는 AVAILABLE, 초과는 LOCKED로 표시된다")
	void marksAvailableWithinParticipationRadiusAndLockedBeyond() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID availablePlace = insertProjectedPlace("참여 가능", 99.9);
		UUID lockedPlace = insertProjectedPlace("잠김", 100.1);
		UUID availableMission = insertOxMission(availablePlace, "참여 가능 미션", 100, null, true);
		UUID lockedMission = insertOxMission(lockedPlace, "잠긴 미션", 100, null, true);

		MvcResult result = mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk()).andReturn();
		assertThat(itemFor(result, availableMission).get("availability").stringValue()).isEqualTo("AVAILABLE");
		assertThat(itemFor(result, lockedMission).get("availability").stringValue()).isEqualTo("LOCKED");
	}

	/** 완료·기회 소진 기록은 현재 위치와 무관하게 유지된다. */
	@Test
	@DisplayName("완료·기회 소진 상태는 위치와 관계없이 유지된다")
	void completedAndExhaustedOverrideLocationBasedAvailability() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID farPlace = insertProjectedPlace("먼 장소", 490);
		UUID completedMission = insertOxMission(farPlace, "완료 미션", 100, null, true);
		UUID exhaustedMission = insertOxMission(farPlace, "소진 미션", 100, 1, true);
		insertParticipation(tripId, completedMission, "COMPLETED", 1);
		insertParticipation(tripId, exhaustedMission, "EXHAUSTED", 1);

		MvcResult result = mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk()).andReturn();
		assertThat(itemFor(result, completedMission).get("availability").stringValue()).isEqualTo("COMPLETED");
		assertThat(itemFor(result, exhaustedMission).get("availability").stringValue()).isEqualTo("EXHAUSTED");
	}

	/** 도전 가능한 스페셜 퀴즈의 남은 횟수는 최대 횟수에서 사용 횟수를 뺀 값이다. */
	@Test
	@DisplayName("도전 가능한 스페셜 퀴즈는 남은 횟수를 최대 횟수에서 사용 횟수를 뺀 값으로 받는다")
	void computesRemainingAttemptsForChallengeableSpecialQuiz() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertPlace("스페셜 퀴즈 장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE);
		UUID missionId = insertOxMission(place, "스페셜 퀴즈", 100, 3, true);
		insertParticipation(tripId, missionId, "AVAILABLE", 1);

		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].remainingAttempts").value(2));
	}

	/** 완료 또는 기회 소진된 스페셜 퀴즈의 남은 횟수는 0이다. */
	@Test
	@DisplayName("완료·소진된 스페셜 퀴즈의 남은 횟수는 0이다")
	void remainingAttemptsIsZeroForCompletedOrExhaustedSpecialQuiz() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertPlace("스페셜 퀴즈 장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE);
		UUID missionId = insertOxMission(place, "스페셜 퀴즈", 100, 3, true);
		insertParticipation(tripId, missionId, "EXHAUSTED", 3);

		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].remainingAttempts").value(0));
	}

	/** 일반 퀴즈와 사진 인증 미션의 남은 도전 횟수는 항상 null이다. */
	@Test
	@DisplayName("일반 퀴즈와 사진 미션의 남은 횟수는 null이다")
	void remainingAttemptsIsNullForNormalQuizAndPhotoMission() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertPlace("장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE);
		UUID normalQuiz = insertOxMission(place, "일반 퀴즈", 100, null, true);
		UUID photoMission = insertPhotoMission(place, "사진 미션", 100);
		insertParticipation(tripId, normalQuiz, "COMPLETED", 1);

		MvcResult result = mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk()).andReturn();
		assertThat(itemFor(result, normalQuiz).get("remainingAttempts").isNull()).isTrue();
		assertThat(itemFor(result, photoMission).get("remainingAttempts").isNull()).isTrue();
	}

	/** 조회는 참여 행을 생성하거나 기존 DB 상태를 바꾸지 않는다. */
	@Test
	@DisplayName("반복 조회 전후 mission_participations를 포함한 DB 상태가 동일하다")
	void repeatedRequestsDoNotChangeDbState() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertPlace("장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE);
		insertOxMission(place, "OX", 100, null, true);

		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk());
		mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk());

		Integer participationCount = jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM mission_participations WHERE trip_id = ?", Integer.class, tripId);
		assertThat(participationCount).isZero();
	}

	/** 좌표나 tripId 형식이 잘못되면 400을 받는다. */
	@Test
	@DisplayName("좌표나 tripId 형식이 잘못되면 400을 받는다")
	void returns400WhenCoordinatesOrTripIdAreInvalid() throws Exception {
		UUID tripId = startTrip(member.memberId());

		mockMvc.perform(get("/missions/nearby").header("Authorization", "Bearer " + member.accessToken())
				.param("latitude", "abc").param("longitude", String.valueOf(ORIGIN_LONGITUDE))
				.param("tripId", tripId.toString())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));

		mockMvc.perform(get("/missions/nearby").header("Authorization", "Bearer " + member.accessToken())
				.param("latitude", "91.0").param("longitude", String.valueOf(ORIGIN_LONGITUDE))
				.param("tripId", tripId.toString())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));

		mockMvc.perform(get("/missions/nearby").header("Authorization", "Bearer " + member.accessToken())
				.param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude", String.valueOf(ORIGIN_LONGITUDE))
				.param("tripId", "not-a-uuid")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));

		mockMvc.perform(get("/missions/nearby").header("Authorization", "Bearer " + member.accessToken())
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("tripId", tripId.toString()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** 인증되지 않은 요청은 401을 받는다. */
	@Test
	@DisplayName("인증하지 않으면 401을 받는다")
	void returns401WhenUnauthenticated() throws Exception {
		mockMvc.perform(get("/missions/nearby").param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("tripId", UUID.randomUUID().toString()))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 존재하지 않거나 다른 회원의 여행은 존재 여부를 구분하지 않고 404를 받는다. */
	@Test
	@DisplayName("본인 소유가 아니거나 존재하지 않는 여행은 404를 받는다")
	void returns404WhenTripIsNotOwnedOrMissing() throws Exception {
		mockMvc.perform(nearbyRequest(UUID.randomUUID())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));

		AuthenticatedMember other = insertAuthenticatedMember();
		UUID otherTripId = startTrip(other.memberId());
		mockMvc.perform(nearbyRequest(otherTripId)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 본인 여행이지만 종료·정산 상태면 409를 받는다. */
	@Test
	@DisplayName("본인 여행이 진행 중이 아니면 409를 받는다")
	void returns409WhenOwnTripIsNotInProgress() throws Exception {
		UUID endedTripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status, ended_at) VALUES (?, ?, 'ENDED', now())",
				endedTripId, member.memberId());
		mockMvc.perform(nearbyRequest(endedTripId)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("TRIP_NOT_IN_PROGRESS"));

		UUID settledTripId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO trips (id, member_id, status, ended_at, settled_at) VALUES (?, ?, 'SETTLED', now(), now())",
				settledTripId, member.memberId());
		mockMvc.perform(nearbyRequest(settledTripId)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("TRIP_NOT_IN_PROGRESS"));
	}

	/** 반올림 전 거리가 100m 이내인 객관식 미션은 문제와 정답 표시 없는 선택지를 포함한 전체 상세를 받는다. */
	@Test
	@DisplayName("100m 이내 객관식 미션은 문제와 선택지를 포함한 전체 상세를 받고 정답 값은 노출하지 않는다")
	void returnsFullDetailWithChoicesForMultipleChoiceMissionWithin100m() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("객관식 장소", 50);
		UUID missionId = insertMultipleChoiceMission(place, "객관식 미션", 100, null);
		insertChoice(missionId, "예", true, 0);
		insertChoice(missionId, "아니요", false, 1);

		mockMvc.perform(detailRequest(missionId, tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.missionId").value(missionId.toString()))
				.andExpect(jsonPath("$.data.description").value("설명"))
				.andExpect(jsonPath("$.data.choices.length()").value(2))
				.andExpect(jsonPath("$.data.choices[0].label").value("예"))
				.andExpect(jsonPath("$.data.choices[1].label").value("아니요"))
				.andExpect(jsonPath("$.data.choices[0].correct").doesNotExist())
				.andExpect(jsonPath("$.data.choices[1].correct").doesNotExist());
	}

	/** 반올림 전 거리가 100m 이내인 OX 미션은 문제를 포함한 전체 상세를 받고 선택지 필드는 없다. */
	@Test
	@DisplayName("100m 이내 OX 미션은 문제를 포함한 전체 상세를 받고 정답 값은 노출하지 않는다")
	void returnsFullDetailForOxMissionWithin100m() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("OX 장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(detailRequest(missionId, tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.description").value("설명"))
				.andExpect(jsonPath("$.data.choices").doesNotExist())
				.andExpect(jsonPath("$.data.oxCorrectAnswer").doesNotExist());
	}

	/** 반올림 전 거리가 100m 이내인 사진 인증 미션은 촬영 안내를 포함한 전체 상세를 받는다. */
	@Test
	@DisplayName("100m 이내 사진 미션은 촬영 안내를 포함한 전체 상세를 받는다")
	void returnsFullDetailForPhotoMissionWithin100m() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("사진 장소", 50);
		UUID missionId = insertPhotoMission(place, "사진 미션", 150);

		mockMvc.perform(detailRequest(missionId, tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.description").value("설명"));
	}

	/** 실제 거리가 100m를 초과하면 문제와 선택지가 없는 제한 상세를 받는다. */
	@Test
	@DisplayName("100m를 초과하면 description과 choices가 없는 제한 상세를 받는다")
	void returnsRestrictedDetailBeyond100m() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("잠긴 장소", 100.1);
		UUID missionId = insertMultipleChoiceMission(place, "객관식 미션", 100, null);
		insertChoice(missionId, "예", true, 0);
		insertChoice(missionId, "아니요", false, 1);

		mockMvc.perform(detailRequest(missionId, tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.missionId").value(missionId.toString()))
				.andExpect(jsonPath("$.data.availability").value("LOCKED"))
				.andExpect(jsonPath("$.data.description").doesNotExist())
				.andExpect(jsonPath("$.data.choices").doesNotExist());
	}

	/** 500m 탐색 반경 밖의 존재하는 미션도 제한 상세로 200을 받는다. */
	@Test
	@DisplayName("500m 밖의 미션도 제한 상세 200으로 조회할 수 있다")
	void returnsRestrictedDetailForMissionOutside500mRadius() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("먼 장소", 600);
		UUID missionId = insertOxMission(place, "먼 미션", 100, null, true);

		mockMvc.perform(detailRequest(missionId, tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.missionId").value(missionId.toString()))
				.andExpect(jsonPath("$.data.description").doesNotExist());
	}

	/** 완료·기회 소진 미션도 100m 밖이면 상태와 남은 횟수는 유지하되 제한 상세만 받는다. */
	@Test
	@DisplayName("완료·소진 미션도 100m 밖이면 상태는 유지하되 제한 상세만 받는다")
	void completedAndExhaustedMissionsBeyond100mReturnRestrictedDetailWithPreservedStatus() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("먼 장소", 200);
		UUID completedMission = insertOxMission(place, "완료 미션", 100, null, true);
		UUID exhaustedMission = insertOxMission(place, "소진 미션", 100, 3, true);
		insertParticipation(tripId, completedMission, "COMPLETED", 1);
		insertParticipation(tripId, exhaustedMission, "EXHAUSTED", 3);

		mockMvc.perform(detailRequest(completedMission, tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.availability").value("COMPLETED"))
				.andExpect(jsonPath("$.data.description").doesNotExist());
		mockMvc.perform(detailRequest(exhaustedMission, tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.availability").value("EXHAUSTED"))
				.andExpect(jsonPath("$.data.remainingAttempts").value(0))
				.andExpect(jsonPath("$.data.description").doesNotExist());
	}

	/** 목록과 상세의 장소명·좌표, 거리, 보상, 표시 상태, 참여 반경과 남은 횟수는 같은 규칙으로 계산된다. */
	@Test
	@DisplayName("목록과 상세의 공통 필드는 동일한 값을 반환한다")
	void listAndDetailShareTheSameComputedFields() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("공통 장소", 50);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		MvcResult listResult = mockMvc.perform(nearbyRequest(tripId)).andExpect(status().isOk()).andReturn();
		JsonNode item = itemFor(listResult, missionId);

		mockMvc.perform(detailRequest(missionId, tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.placeName").value(item.get("placeName").stringValue()))
				.andExpect(jsonPath("$.data.latitude").value(item.get("latitude").doubleValue()))
				.andExpect(jsonPath("$.data.longitude").value(item.get("longitude").doubleValue()))
				.andExpect(jsonPath("$.data.distanceMeters").value(item.get("distanceMeters").intValue()))
				.andExpect(jsonPath("$.data.rewardPoints").value(item.get("rewardPoints").intValue()))
				.andExpect(jsonPath("$.data.availability").value(item.get("availability").stringValue()))
				.andExpect(jsonPath("$.data.participationRadiusMeters")
						.value(item.get("participationRadiusMeters").intValue()));
	}

	/** 반복 상세 조회 전후 mission_participations를 포함한 DB 상태가 동일하다. */
	@Test
	@DisplayName("반복 상세 조회 전후 DB 상태가 동일하다")
	void repeatedDetailRequestsDoNotChangeDbState() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX", 100, null, true);

		mockMvc.perform(detailRequest(missionId, tripId)).andExpect(status().isOk());
		mockMvc.perform(detailRequest(missionId, tripId)).andExpect(status().isOk());

		Integer participationCount = jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM mission_participations WHERE trip_id = ?", Integer.class, tripId);
		assertThat(participationCount).isZero();
	}

	/** 좌표, tripId, missionId 형식이 잘못되면 400을 받는다. */
	@Test
	@DisplayName("좌표, tripId, missionId 형식이 잘못되면 400을 받는다")
	void returns400WhenDetailRequestParametersAreInvalid() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX", 100, null, true);

		mockMvc.perform(
				get("/missions/{missionId}", "not-a-uuid").header("Authorization", "Bearer " + member.accessToken())
						.param("latitude", String.valueOf(ORIGIN_LATITUDE))
						.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("tripId", tripId.toString()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));

		mockMvc.perform(get("/missions/{missionId}", missionId)
				.header("Authorization", "Bearer " + member.accessToken()).param("latitude", "abc")
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("tripId", tripId.toString()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** 인증되지 않은 요청은 401을 받는다. */
	@Test
	@DisplayName("인증하지 않고 상세를 조회하면 401을 받는다")
	void returns401WhenDetailRequestIsUnauthenticated() throws Exception {
		mockMvc.perform(get("/missions/{missionId}", UUID.randomUUID())
				.param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude", String.valueOf(ORIGIN_LONGITUDE))
				.param("tripId", UUID.randomUUID().toString())).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 존재하지 않거나 다른 회원의 여행은 존재 여부를 구분하지 않고 404를 받는다. */
	@Test
	@DisplayName("본인 소유가 아니거나 존재하지 않는 여행으로 상세를 조회하면 404를 받는다")
	void returns404WhenTripIsNotOwnedOrMissingForDetailRequest() throws Exception {
		mockMvc.perform(detailRequest(UUID.randomUUID(), UUID.randomUUID())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 존재하지 않는 미션은 404를 받는다. */
	@Test
	@DisplayName("존재하지 않는 미션을 조회하면 404를 받는다")
	void returns404WhenMissionDoesNotExist() throws Exception {
		UUID tripId = startTrip(member.memberId());

		mockMvc.perform(detailRequest(UUID.randomUUID(), tripId)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 본인 여행이지만 종료·정산 상태면 409를 받는다. */
	@Test
	@DisplayName("본인 여행이 진행 중이 아니면 상세 조회도 409를 받는다")
	void returns409WhenOwnTripIsNotInProgressForDetailRequest() throws Exception {
		UUID place = insertProjectedPlace("장소", 50);
		UUID missionId = insertOxMission(place, "OX", 100, null, true);
		UUID endedTripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status, ended_at) VALUES (?, ?, 'ENDED', now())",
				endedTripId, member.memberId());

		mockMvc.perform(detailRequest(missionId, endedTripId)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("TRIP_NOT_IN_PROGRESS"));
	}

	private JsonNode itemFor(MvcResult result, UUID missionId) throws Exception {
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("items");
		for (JsonNode item : items) {
			if (item.get("missionId").stringValue().equals(missionId.toString())) {
				return item;
			}
		}
		throw new AssertionError("응답에서 미션을 찾을 수 없습니다: " + missionId);
	}

	private MockHttpServletRequestBuilder nearbyRequest(UUID tripId) {
		return get("/missions/nearby").header("Authorization", "Bearer " + member.accessToken())
				.param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude", String.valueOf(ORIGIN_LONGITUDE))
				.param("tripId", tripId.toString());
	}

	private MockHttpServletRequestBuilder detailRequest(UUID missionId, UUID tripId) {
		return get("/missions/{missionId}", missionId).header("Authorization", "Bearer " + member.accessToken())
				.param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude", String.valueOf(ORIGIN_LONGITUDE))
				.param("tripId", tripId.toString());
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

	private UUID insertMultipleChoiceMission(UUID placeId, String title, int rewardPoints, Integer maxAttempts) {
		return insertMission(placeId, "MULTIPLE_CHOICE", title, rewardPoints, maxAttempts, null);
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

	private void insertChoice(UUID missionId, String label, boolean correct, int sortOrder) {
		jdbcTemplate.update(
				"INSERT INTO mission_choices (id, mission_id, label, correct, sort_order) VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID(), missionId, label, correct, sortOrder);
	}

	private void insertParticipation(UUID tripId, UUID missionId, String status, int attemptCount) {
		Timestamp completedAt = "COMPLETED".equals(status) ? Timestamp.from(Instant.now()) : null;
		jdbcTemplate.update(
				"INSERT INTO mission_participations (id, trip_id, mission_id, status, attempt_count, completed_at) "
						+ "VALUES (?, ?, ?, ?::mission_status, ?, ?)",
				UUID.randomUUID(), tripId, missionId, status, attemptCount, completedAt);
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
