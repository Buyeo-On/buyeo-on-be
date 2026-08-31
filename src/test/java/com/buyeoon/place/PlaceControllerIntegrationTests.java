package com.buyeoon.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.place.entity.PlaceCategory;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PlaceControllerIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	// 시드 마이그레이션이 부여 지역에 장소 데이터를 채워두므로, 격리를 위해 남극 인근 좌표를
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
		jdbcTemplate.update("DELETE FROM saved_places");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 거리 정렬은 PostGIS ST_Distance 결과를 그대로 따르므로 가까운 장소가 먼저 나온다. */
	@Test
	@DisplayName("진행 중인 여행이 있는 회원은 거리순으로 정렬된 장소 목록을 받는다")
	void returnsNearbyPlacesOrderedByDistanceForMemberWithActiveTrip() throws Exception {
		startTrip(member.memberId());
		UUID farPlace = savePlace(PlaceCategory.HERITAGE, "먼 장소", ORIGIN_LATITUDE + 0.05, ORIGIN_LONGITUDE + 0.05);
		UUID nearPlace = savePlace(PlaceCategory.HERITAGE, "가까운 장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.items[0].placeId").value(nearPlace.toString()))
				.andExpect(jsonPath("$.data.items[0].distanceMeters", greaterThan(-1)))
				.andExpect(jsonPath("$.data.items[1].placeId").value(farPlace.toString()));
	}

	/** detailInfo2 이용안내가 저장돼 있으면 목록 응답의 detailInfo 필드로 그대로 나간다. */
	@Test
	@DisplayName("이용안내가 있는 장소는 detailInfo가 응답에 포함된다")
	void includesDetailInfoWhenPresent() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "이용안내 있는 장소", ORIGIN_LATITUDE + 0.001,
				ORIGIN_LONGITUDE + 0.001);
		jdbcTemplate.update("UPDATE places SET detail_info = ?::jsonb WHERE id = ?",
				"{\"입장료\": \"무료\", \"화장실\": \"있음\"}", placeId);

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].detailInfo.입장료").value("무료"))
				.andExpect(jsonPath("$.data.items[0].detailInfo.화장실").value("있음"));
	}

	/** 이용안내가 없는 장소(default '{}')는 detailInfo가 빈 객체로 나가지 null이 아니다. */
	@Test
	@DisplayName("이용안내가 없으면 detailInfo는 빈 객체로 나간다")
	void includesEmptyDetailInfoWhenAbsent() throws Exception {
		startTrip(member.memberId());
		savePlace(PlaceCategory.HERITAGE, "이용안내 없는 장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].detailInfo").isMap())
				.andExpect(jsonPath("$.data.items[0].detailInfo").isEmpty());
	}

	/**
	 * 커서 다음 페이지의 동점 조건이 등호(=)면 재계산된 거리가 원래 값과 부동소수점 마지막 자리까지 일치하지 않는 경우 동점 그룹 나머지가
	 * 통째로 누락된다. 좌표가 완전히 같은 두 장소로 동점을 만들어 id 가드(>=+id>)가 실제로 그 그룹을 넘기는지 확인한다.
	 */
	@Test
	@DisplayName("같은 거리의 장소가 커서 경계에 걸쳐 있어도 누락되지 않는다")
	void returnsTiedDistancePlacesAcrossCursorBoundary() throws Exception {
		startTrip(member.memberId());
		double tiedLatitude = ORIGIN_LATITUDE + 0.001;
		double tiedLongitude = ORIGIN_LONGITUDE + 0.001;
		UUID first = savePlace(PlaceCategory.HERITAGE, "동점1", tiedLatitude, tiedLongitude);
		UUID second = savePlace(PlaceCategory.HERITAGE, "동점2", tiedLatitude, tiedLongitude);
		UUID third = savePlace(PlaceCategory.HERITAGE, "동점3", tiedLatitude, tiedLongitude);

		MvcResult firstPage = mockMvc
				.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
						.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("size", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.page.hasNext").value(true)).andReturn();
		String nextCursor = objectMapper.readTree(firstPage.getResponse().getContentAsString()).get("data").get("page")
				.get("nextCursor").stringValue();

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("size", "2").param("cursor", nextCursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("size", "10")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[*].placeId").value(hasItem(first.toString())))
				.andExpect(jsonPath("$.data.items[*].placeId").value(hasItem(second.toString())))
				.andExpect(jsonPath("$.data.items[*].placeId").value(hasItem(third.toString())));
	}

	/** 장소 탐색은 진행 중인 여행이 있는 회원만 이용할 수 있다. */
	@Test
	@DisplayName("진행 중인 여행이 없으면 403을 받는다")
	void returns403WhenMemberHasNoActiveTrip() throws Exception {
		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data.code").value("ACTIVE_TRIP_REQUIRED"));
	}

	/** 인증되지 않은 요청은 장소 목록을 볼 수 없다. */
	@Test
	@DisplayName("인증하지 않으면 401을 받는다")
	void returns401WhenUnauthenticated() throws Exception {
		mockMvc.perform(get("/places").param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** latitude·longitude는 필수 파라미터다. */
	@Test
	@DisplayName("좌표가 없으면 400을 받는다")
	void returns400WhenCoordinatesMissing() throws Exception {
		startTrip(member.memberId());

		mockMvc.perform(placeRequest()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** size와 cursor는 신뢰 경계의 입력이므로 잘못된 값은 500이 아니라 400으로 거른다. */
	@Test
	@DisplayName("잘못된 size나 cursor는 400을 받는다")
	void returns400WhenPagingParametersAreInvalid() throws Exception {
		startTrip(member.memberId());

		for (String size : new String[]{"0", "-1", "101"}) {
			mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
					.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("size", size))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		}
		for (String cursor : new String[]{"abc", "!!!", "MTIz"}) {
			mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
					.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("cursor", cursor))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		}
	}

	/** 알 수 없는 카테고리나 범위를 벗어난 좌표는 500이 아니라 400으로 거른다. */
	@Test
	@DisplayName("잘못된 카테고리나 좌표는 400을 받는다")
	void returns400WhenCategoryOrCoordinatesAreInvalid() throws Exception {
		startTrip(member.memberId());

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("category", "BANANA"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));

		mockMvc.perform(placeRequest().param("latitude", "abc").param("longitude", String.valueOf(ORIGIN_LONGITUDE)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));

		mockMvc.perform(placeRequest().param("latitude", "91.0").param("longitude", String.valueOf(ORIGIN_LONGITUDE)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** category를 지정하면 해당 카테고리 장소만 반환한다. */
	@Test
	@DisplayName("카테고리로 필터링된 장소 목록을 받는다")
	void returnsPlacesFilteredByCategory() throws Exception {
		startTrip(member.memberId());
		savePlace(PlaceCategory.HERITAGE, "유적지", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);
		savePlace(PlaceCategory.CAFE, "카페", ORIGIN_LATITUDE + 0.002, ORIGIN_LONGITUDE + 0.002);

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("category", "CAFE"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].category").value("CAFE"));
	}

	/**
	 * soft delete된 장소는 사용자용 목록에서 제외된다. 시드 마이그레이션이 넣어 둔 부여 장소도 같은 카테고리로 함께 잡히므로, 전체
	 * 개수가 아니라 삭제된 장소의 노출 여부만 확인한다.
	 */
	@Test
	@DisplayName("soft delete된 장소는 목록에서 제외된다")
	void excludesSoftDeletedPlaceFromList() throws Exception {
		startTrip(member.memberId());
		UUID visible = savePlace(PlaceCategory.HERITAGE, "노출 장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);
		UUID deleted = savePlace(PlaceCategory.HERITAGE, "삭제된 장소", ORIGIN_LATITUDE + 0.002, ORIGIN_LONGITUDE + 0.002);
		softDeletePlace(deleted);

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[?(@.placeId=='" + visible + "')]").exists())
				.andExpect(jsonPath("$.data.items[?(@.placeId=='" + deleted + "')]").doesNotExist());
	}

	private void softDeletePlace(UUID placeId) {
		jdbcTemplate.update("UPDATE places SET deleted_at = now() WHERE id = ?", placeId);
	}

	/** size만큼 끊어 반환하고, 커서로 이어받으면 남은 장소가 중복 없이 나온다. */
	@Test
	@DisplayName("size만큼 페이지네이션된 결과와 다음 커서를 받는다")
	void returnsPaginatedResultsWithNextCursor() throws Exception {
		startTrip(member.memberId());
		UUID first = savePlace(PlaceCategory.HERITAGE, "장소1", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);
		UUID second = savePlace(PlaceCategory.HERITAGE, "장소2", ORIGIN_LATITUDE + 0.002, ORIGIN_LONGITUDE + 0.002);
		UUID third = savePlace(PlaceCategory.HERITAGE, "장소3", ORIGIN_LATITUDE + 0.003, ORIGIN_LONGITUDE + 0.003);

		MvcResult firstPage = mockMvc
				.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
						.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("size", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[0].placeId").value(first.toString()))
				.andExpect(jsonPath("$.data.items[1].placeId").value(second.toString()))
				.andExpect(jsonPath("$.data.page.hasNext").value(true))
				.andExpect(jsonPath("$.data.page.nextCursor").isString()).andReturn();

		String nextCursor = objectMapper.readTree(firstPage.getResponse().getContentAsString()).get("data").get("page")
				.get("nextCursor").stringValue();

		// 시드 마이그레이션이 넣어 둔 부여 장소도 뒤쪽에 함께 잡히므로, 커서 다음 페이지의 첫
		// 항목이 남은 내 장소인지와 앞 페이지가 다시 나오지 않는지만 확인한다.
		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("size", "2").param("cursor", nextCursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].placeId").value(third.toString()))
				.andExpect(jsonPath("$.data.items[*].placeId").value(not(hasItem(first.toString()))))
				.andExpect(jsonPath("$.data.items[*].placeId").value(not(hasItem(second.toString()))));
	}

	/** saved는 요청한 회원이 저장한 장소에서만 참이고, 다른 회원의 저장은 영향을 주지 않는다. */
	@Test
	@DisplayName("저장한 장소만 saved가 참으로 표시된다")
	void marksOnlyPlacesSavedByRequestingMember() throws Exception {
		startTrip(member.memberId());
		UUID savedPlace = savePlace(PlaceCategory.HERITAGE, "저장한 장소", ORIGIN_LATITUDE + 0.001,
				ORIGIN_LONGITUDE + 0.001);
		UUID otherPlace = savePlace(PlaceCategory.HERITAGE, "저장 안 한 장소", ORIGIN_LATITUDE + 0.002,
				ORIGIN_LONGITUDE + 0.002);
		savePlaceForMember(member.memberId(), savedPlace);
		savePlaceForMember(insertAuthenticatedMember().memberId(), otherPlace);

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].placeId").value(savedPlace.toString()))
				.andExpect(jsonPath("$.data.items[0].saved").value(true))
				.andExpect(jsonPath("$.data.items[1].placeId").value(otherPlace.toString()))
				.andExpect(jsonPath("$.data.items[1].saved").value(false));
	}

	/** 목록의 각 장소는 거리와 도보 시간을 함께 제공한다. */
	@Test
	@DisplayName("각 장소 응답에 거리와 도보 시간이 포함된다")
	void includesDistanceAndWalkingMinutesInResponse() throws Exception {
		startTrip(member.memberId());
		savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);

		mockMvc.perform(placeRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].distanceMeters").exists())
				.andExpect(jsonPath("$.data.items[0].walkingMinutes").exists());
	}

	/** 진행 중인 여행이 있는 회원이 존재하는 장소를 저장하면 200과 함께 저장 상태가 된다. */
	@Test
	@DisplayName("장소를 저장하면 200과 함께 저장 상태가 된다")
	void savesPlaceAndReturns200() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);

		mockMvc.perform(savePlaceRequest(placeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		assertSavedPlaceCount(member.memberId(), placeId, 1);
	}

	/** 이미 저장한 장소를 다시 저장해도 200이며 저장 관계는 하나만 유지된다. */
	@Test
	@DisplayName("이미 저장한 장소를 다시 저장해도 200이고 저장 관계는 하나만 유지된다")
	void savingAlreadySavedPlaceStaysIdempotent() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		savePlaceForMember(member.memberId(), placeId);

		mockMvc.perform(savePlaceRequest(placeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		assertSavedPlaceCount(member.memberId(), placeId, 1);
	}

	/** 진행 중인 여행이 없는 회원이 저장을 요청하면 403을 반환한다. */
	@Test
	@DisplayName("진행 중인 여행이 없으면 저장 요청은 403을 받는다")
	void returns403WhenSavingWithoutActiveTrip() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);

		mockMvc.perform(savePlaceRequest(placeId)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("ACTIVE_TRIP_REQUIRED"));
	}

	/** 존재하지 않는 placeId로 저장을 요청하면 404를 반환한다. */
	@Test
	@DisplayName("존재하지 않는 장소를 저장하면 404를 받는다")
	void returns404WhenSavingNonExistentPlace() throws Exception {
		startTrip(member.memberId());

		mockMvc.perform(savePlaceRequest(UUID.randomUUID())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 로그인하지 않은 요청은 401을 반환한다. */
	@Test
	@DisplayName("인증하지 않으면 저장 요청은 401을 받는다")
	void returns401WhenSavingUnauthenticated() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);

		mockMvc.perform(put("/members/me/saved-places/{placeId}", placeId)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 같은 장소에 대한 동시 저장 요청 두 건 모두 200을 반환하고 저장 관계는 하나만 남는다. */
	@Test
	@DisplayName("같은 장소에 대한 동시 저장 요청은 모두 200이고 저장 관계는 하나만 남는다")
	void concurrentSaveRequestsBothSucceedAndLeaveOneRow() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> results = executor
					.invokeAll(List.of(() -> saveStatus(placeId), () -> saveStatus(placeId)));
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(200);
			}
		} finally {
			executor.shutdown();
		}

		assertSavedPlaceCount(member.memberId(), placeId, 1);
	}

	private int saveStatus(UUID placeId) throws Exception {
		return mockMvc.perform(savePlaceRequest(placeId)).andReturn().getResponse().getStatus();
	}

	/** 저장한 장소를 삭제하면 200을 반환하고 저장 관계가 제거된다. */
	@Test
	@DisplayName("저장한 장소를 삭제하면 200과 함께 저장 관계가 제거된다")
	void deletesSavedPlaceAndReturns200() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		savePlaceForMember(member.memberId(), placeId);

		mockMvc.perform(deleteSavedPlaceRequest(placeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		assertSavedPlaceCount(member.memberId(), placeId, 0);
	}

	/** 저장하지 않은 장소를 삭제해도 이미 목표 상태이므로 200을 반환한다(멱등). */
	@Test
	@DisplayName("저장하지 않은 장소를 삭제해도 200을 반환한다")
	void deletingUnsavedPlaceStaysIdempotent() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);

		mockMvc.perform(deleteSavedPlaceRequest(placeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));
	}

	/** 저장한 장소를 삭제해도 장소 자체와 방문 기록은 유지된다. */
	@Test
	@DisplayName("저장한 장소를 삭제해도 장소 데이터는 유지된다")
	void deletingSavedPlaceKeepsPlaceData() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		savePlaceForMember(member.memberId(), placeId);

		mockMvc.perform(deleteSavedPlaceRequest(placeId)).andExpect(status().isOk());

		Integer placeCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM places WHERE id = ?", Integer.class,
				placeId);
		assertThat(placeCount).isEqualTo(1);
	}

	/** 진행 중인 여행이 없는 회원도 삭제할 수 있다(403 없음). */
	@Test
	@DisplayName("진행 중인 여행이 없어도 삭제 요청은 200을 받는다")
	void deletesSavedPlaceWithoutActiveTrip() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		savePlaceForMember(member.memberId(), placeId);

		mockMvc.perform(deleteSavedPlaceRequest(placeId)).andExpect(status().isOk());
	}

	/** 다른 회원이 저장한 장소를 삭제 요청해도 요청자 자신의 저장 관계만 대상이 된다. */
	@Test
	@DisplayName("다른 회원의 저장 관계는 삭제되지 않는다")
	void deletingSavedPlaceDoesNotAffectOtherMembers() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		AuthenticatedMember otherMember = insertAuthenticatedMember();
		savePlaceForMember(otherMember.memberId(), placeId);

		mockMvc.perform(deleteSavedPlaceRequest(placeId)).andExpect(status().isOk());

		assertSavedPlaceCount(otherMember.memberId(), placeId, 1);
	}

	/** 같은 장소에 대한 저장과 삭제가 동시에 요청되면 나중에 확정된 요청의 상태를 따른다. */
	@Test
	@DisplayName("동시 저장·삭제 요청 후 저장 관계 존재 여부는 둘 중 하나로 일관된다")
	void concurrentSaveAndDeleteRequestsBothSucceedWithConsistentFinalState() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		savePlaceForMember(member.memberId(), placeId);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> results = executor
					.invokeAll(List.of(() -> saveStatus(placeId), () -> deleteStatus(placeId)));
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(200);
			}
		} finally {
			executor.shutdown();
		}

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM saved_places WHERE member_id = ? AND place_id = ?", Integer.class,
				member.memberId(), placeId);
		assertThat(count).isIn(0, 1);
	}

	/** 로그인하지 않은 삭제 요청은 401을 반환한다. */
	@Test
	@DisplayName("인증하지 않으면 삭제 요청은 401을 받는다")
	void returns401WhenDeletingUnauthenticated() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);

		mockMvc.perform(delete("/members/me/saved-places/{placeId}", placeId)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private int deleteStatus(UUID placeId) throws Exception {
		return mockMvc.perform(deleteSavedPlaceRequest(placeId)).andReturn().getResponse().getStatus();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deleteSavedPlaceRequest(
			UUID placeId) {
		return delete("/members/me/saved-places/{placeId}", placeId).header("Authorization",
				"Bearer " + member.accessToken());
	}

	private void assertSavedPlaceCount(UUID memberId, UUID placeId, int expectedCount) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM saved_places WHERE member_id = ? AND place_id = ?", Integer.class, memberId,
				placeId);
		assertThat(count).isEqualTo(expectedCount);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder savePlaceRequest(UUID placeId) {
		return put("/members/me/saved-places/{placeId}", placeId).header("Authorization",
				"Bearer " + member.accessToken());
	}

	/** 진행 중인 여행이 있는 회원은 존재하는 장소의 상세를 받는다. */
	@Test
	@DisplayName("유효한 위치·진행 중 여행으로 요청하면 200과 함께 장소 상세를 받는다")
	void returnsPlaceDetailForMemberWithActiveTrip() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);

		mockMvc.perform(placeDetailRequest(placeId).param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.placeId").value(placeId.toString()))
				.andExpect(jsonPath("$.data.name").value("장소")).andExpect(jsonPath("$.data.category").value("HERITAGE"))
				.andExpect(jsonPath("$.data.distanceMeters").exists())
				.andExpect(jsonPath("$.data.walkingMinutes").exists()).andExpect(jsonPath("$.data.saved").value(false));
	}

	@Test
	@DisplayName("TourAPI 대표이미지는 제공기관과 이용허락 유형을 함께 반환한다")
	void returnsAttributionForTourApiImage() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "출처 있는 장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);
		jdbcTemplate.update("""
				UPDATE places
				SET source_name = 'TOUR_API',
				    source_image_href = 'https://tourapi.example.com/image.jpg',
				    source_image_license_type = 'KOGL_TYPE_3'
				WHERE id = ?
				""", placeId);

		mockMvc.perform(placeDetailRequest(placeId).param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imageUrl").value("https://tourapi.example.com/image.jpg"))
				.andExpect(jsonPath("$.data.imageAttribution.provider").value("한국관광공사"))
				.andExpect(jsonPath("$.data.imageAttribution.service").value("TourAPI"))
				.andExpect(jsonPath("$.data.imageAttribution.licenseType").value("KOGL_TYPE_3"))
				.andExpect(jsonPath("$.data.imageAttribution.sourceUrl")
						.value("https://www.data.go.kr/data/15101578/openapi.do"));
	}

	@Test
	@DisplayName("관리자 이미지가 표시되면 TourAPI 이미지 출처를 반환하지 않는다")
	void omitsTourApiAttributionWhenAdminImageOverridesSourceImage() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "관리자 이미지 장소", ORIGIN_LATITUDE + 0.001,
				ORIGIN_LONGITUDE + 0.001);
		jdbcTemplate.update("""
				UPDATE places
				SET image_key = 'public/places/admin-image.jpg',
				    source_name = 'TOUR_API',
				    source_image_href = 'https://tourapi.example.com/image.jpg',
				    source_image_license_type = 'KOGL_TYPE_1'
				WHERE id = ?
				""", placeId);

		mockMvc.perform(placeDetailRequest(placeId).param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imageUrl").isString())
				.andExpect(jsonPath("$.data.imageAttribution").doesNotExist());
	}

	/** 요청 회원이 저장한 장소는 saved가 참으로 표시된다. */
	@Test
	@DisplayName("저장한 장소의 상세 조회는 saved가 true를 받는다")
	void returnsSavedTrueForPlaceSavedByRequestingMember() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);
		savePlaceForMember(member.memberId(), placeId);

		mockMvc.perform(placeDetailRequest(placeId).param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.saved").value(true));
	}

	/** 장소 탐색은 진행 중인 여행이 있는 회원만 이용할 수 있다. */
	@Test
	@DisplayName("장소 상세 조회는 진행 중인 여행이 없으면 403을 받는다")
	void returns403ForPlaceDetailWhenMemberHasNoActiveTrip() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);

		mockMvc.perform(placeDetailRequest(placeId).param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("ACTIVE_TRIP_REQUIRED"));
	}

	/** 인증되지 않은 요청은 장소 상세를 볼 수 없다. */
	@Test
	@DisplayName("장소 상세 조회는 인증하지 않으면 401을 받는다")
	void returns401ForPlaceDetailWhenUnauthenticated() throws Exception {
		UUID placeId = UUID.randomUUID();

		mockMvc.perform(get("/places/" + placeId).param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** latitude·longitude는 장소 상세 조회에서도 필수 파라미터다. */
	@Test
	@DisplayName("장소 상세 조회는 좌표가 없으면 400을 받는다")
	void returns400ForPlaceDetailWhenCoordinatesMissing() throws Exception {
		startTrip(member.memberId());
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);

		mockMvc.perform(placeDetailRequest(placeId)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** 존재하지 않는 placeId는 404를 받는다. */
	@Test
	@DisplayName("존재하지 않는 placeId면 404를 받는다")
	void returns404WhenPlaceDoesNotExist() throws Exception {
		startTrip(member.memberId());
		UUID placeId = UUID.randomUUID();

		mockMvc.perform(placeDetailRequest(placeId).param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("위치 없이 저장한 장소를 조회하면 저장 시각 내림차순으로 반환하고 거리는 null이다")
	void returnsSavedPlacesOrderedBySavedAtDescendingWithNullDistance() throws Exception {
		UUID older = savePlace(PlaceCategory.HERITAGE, "먼저 저장", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		UUID newer = savePlace(PlaceCategory.HERITAGE, "나중 저장", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		Instant base = Instant.now();
		savePlaceForMemberAt(member.memberId(), older, base);
		savePlaceForMemberAt(member.memberId(), newer, base.plusSeconds(60));

		mockMvc.perform(savedPlacesRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].placeId").value(newer.toString()))
				.andExpect(jsonPath("$.data.items[1].placeId").value(older.toString()))
				.andExpect(jsonPath("$.data.items[0].distanceMeters").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].walkingMinutes").doesNotExist())
				.andExpect(jsonPath("$.data.page.hasNext").value(false));
	}

	@Test
	@DisplayName("category로 필터링하면 해당 분류의 저장한 장소만 반환한다")
	void returnsSavedPlacesFilteredByCategory() throws Exception {
		UUID heritage = savePlace(PlaceCategory.HERITAGE, "유적지", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		UUID cafe = savePlace(PlaceCategory.CAFE, "카페", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		savePlaceForMember(member.memberId(), heritage);
		savePlaceForMember(member.memberId(), cafe);

		mockMvc.perform(savedPlacesRequest().param("category", "CAFE")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].placeId").value(cafe.toString()));
	}

	/**
	 * 가까운 장소를 먼저, 먼 장소를 나중에 저장해 거리순과 저장 시각순 결과가 서로 반대로 나오게 한다. 정렬이 실수로 거리순으로 바뀌면 이
	 * 테스트가 실패해야 한다.
	 */
	@Test
	@DisplayName("위치와 함께 조회하면 distanceMeters와 walkingMinutes가 채워지고 정렬은 저장 시각순을 유지한다")
	void returnsSavedPlacesWithDistanceWhenLocationProvidedButOrderStaysBySavedAt() throws Exception {
		UUID near = savePlace(PlaceCategory.HERITAGE, "가깝지만 먼저 저장", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);
		UUID far = savePlace(PlaceCategory.HERITAGE, "멀지만 나중 저장", ORIGIN_LATITUDE + 0.05, ORIGIN_LONGITUDE + 0.05);
		Instant base = Instant.now();
		savePlaceForMemberAt(member.memberId(), near, base);
		savePlaceForMemberAt(member.memberId(), far, base.plusSeconds(60));

		mockMvc.perform(savedPlacesRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE)).param("longitude",
				String.valueOf(ORIGIN_LONGITUDE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].placeId").value(far.toString()))
				.andExpect(jsonPath("$.data.items[1].placeId").value(near.toString()))
				.andExpect(jsonPath("$.data.items[0].distanceMeters", greaterThan(-1)))
				.andExpect(jsonPath("$.data.items[1].distanceMeters", greaterThan(-1)));
	}

	@Test
	@DisplayName("위치와 category를 함께 전달하면 해당 분류만 거리·도보 시간과 함께 반환한다")
	void returnsSavedPlacesFilteredByCategoryWithDistance() throws Exception {
		UUID heritage = savePlace(PlaceCategory.HERITAGE, "유적지", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);
		UUID cafe = savePlace(PlaceCategory.CAFE, "카페", ORIGIN_LATITUDE + 0.001, ORIGIN_LONGITUDE + 0.001);
		savePlaceForMember(member.memberId(), heritage);
		savePlaceForMember(member.memberId(), cafe);

		mockMvc.perform(
				savedPlacesRequest().param("category", "CAFE").param("latitude", String.valueOf(ORIGIN_LATITUDE))
						.param("longitude", String.valueOf(ORIGIN_LONGITUDE)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].placeId").value(cafe.toString()))
				.andExpect(jsonPath("$.data.items[0].distanceMeters", greaterThan(-1)));
	}

	@Test
	@DisplayName("위치와 함께 커서로 다음 페이지를 요청해도 저장 시각순으로 이어진다")
	void returnsNextPageOfSavedPlacesWithLocationOrderedBySavedAt() throws Exception {
		UUID first = savePlace(PlaceCategory.HERITAGE, "장소1", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		UUID second = savePlace(PlaceCategory.HERITAGE, "장소2", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		UUID third = savePlace(PlaceCategory.HERITAGE, "장소3", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		Instant base = Instant.now();
		savePlaceForMemberAt(member.memberId(), first, base);
		savePlaceForMemberAt(member.memberId(), second, base.plusSeconds(60));
		savePlaceForMemberAt(member.memberId(), third, base.plusSeconds(120));

		MvcResult firstPage = mockMvc
				.perform(savedPlacesRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
						.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("size", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.page.hasNext").value(true)).andReturn();
		String cursor = objectMapper.readTree(firstPage.getResponse().getContentAsString()).path("data").path("page")
				.path("nextCursor").asString();

		mockMvc.perform(savedPlacesRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE))
				.param("longitude", String.valueOf(ORIGIN_LONGITUDE)).param("size", "2").param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].placeId").value(first.toString()))
				.andExpect(jsonPath("$.data.items[0].distanceMeters", greaterThan(-1)))
				.andExpect(jsonPath("$.data.page.hasNext").value(false));
	}

	@Test
	@DisplayName("latitude/longitude 중 하나만 전달되면 400을 반환한다")
	void returns400WhenOnlyOneCoordinateProvidedForSavedPlaces() throws Exception {
		mockMvc.perform(savedPlacesRequest().param("latitude", String.valueOf(ORIGIN_LATITUDE)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));

		mockMvc.perform(savedPlacesRequest().param("longitude", String.valueOf(ORIGIN_LONGITUDE)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("저장한 장소가 없으면 빈 배열과 hasNext:false를 반환한다")
	void returnsEmptyListWhenNoSavedPlaces() throws Exception {
		mockMvc.perform(savedPlacesRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(0))
				.andExpect(jsonPath("$.data.page.hasNext").value(false));
	}

	@Test
	@DisplayName("커서로 다음 페이지를 요청하면 이전 페이지와 겹치거나 누락 없이 이어진다")
	void returnsNextPageOfSavedPlacesUsingCursor() throws Exception {
		UUID first = savePlace(PlaceCategory.HERITAGE, "장소1", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		UUID second = savePlace(PlaceCategory.HERITAGE, "장소2", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		UUID third = savePlace(PlaceCategory.HERITAGE, "장소3", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		Instant base = Instant.now();
		savePlaceForMemberAt(member.memberId(), first, base);
		savePlaceForMemberAt(member.memberId(), second, base.plusSeconds(60));
		savePlaceForMemberAt(member.memberId(), third, base.plusSeconds(120));

		MvcResult firstPage = mockMvc.perform(savedPlacesRequest().param("size", "2")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.page.hasNext").value(true)).andReturn();
		String cursor = objectMapper.readTree(firstPage.getResponse().getContentAsString()).path("data").path("page")
				.path("nextCursor").asString();

		mockMvc.perform(savedPlacesRequest().param("size", "2").param("cursor", cursor)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].placeId").value(first.toString()))
				.andExpect(jsonPath("$.data.page.hasNext").value(false));
	}

	@Test
	@DisplayName("저장 시각이 동일한 항목이 커서 경계에 걸쳐도 누락이나 중복 없이 반환한다")
	void returnsTiedSavedAtAcrossCursorBoundary() throws Exception {
		UUID first = savePlace(PlaceCategory.HERITAGE, "동점1", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		UUID second = savePlace(PlaceCategory.HERITAGE, "동점2", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		UUID third = savePlace(PlaceCategory.HERITAGE, "동점3", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		Instant tied = Instant.now();
		savePlaceForMemberAt(member.memberId(), first, tied);
		savePlaceForMemberAt(member.memberId(), second, tied);
		savePlaceForMemberAt(member.memberId(), third, tied);

		MvcResult firstPage = mockMvc.perform(savedPlacesRequest().param("size", "2")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.page.hasNext").value(true)).andReturn();
		String cursor = objectMapper.readTree(firstPage.getResponse().getContentAsString()).path("data").path("page")
				.path("nextCursor").asString();

		MvcResult secondPage = mockMvc.perform(savedPlacesRequest().param("size", "2").param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.page.hasNext").value(false)).andReturn();

		List<String> firstIds = extractPlaceIds(firstPage);
		List<String> secondIds = extractPlaceIds(secondPage);
		assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
		List<String> combined = new java.util.ArrayList<>(firstIds);
		combined.addAll(secondIds);
		assertThat(combined).containsExactlyInAnyOrder(first.toString(), second.toString(), third.toString());
	}

	@Test
	@DisplayName("미인증 요청은 401을 반환한다")
	void returns401WhenListingSavedPlacesUnauthenticated() throws Exception {
		mockMvc.perform(get("/members/me/saved-places")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("활성 여행이 없어도 저장한 장소 목록을 조회할 수 있다")
	void listsSavedPlacesWithoutActiveTrip() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		savePlaceForMember(member.memberId(), placeId);

		mockMvc.perform(savedPlacesRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(1));
	}

	@Test
	@DisplayName("다른 회원이 저장한 장소는 목록에 나타나지 않는다")
	void excludesSavedPlacesOfOtherMembers() throws Exception {
		UUID placeId = savePlace(PlaceCategory.HERITAGE, "장소", ORIGIN_LATITUDE, ORIGIN_LONGITUDE);
		savePlaceForMember(insertAuthenticatedMember().memberId(), placeId);

		mockMvc.perform(savedPlacesRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(0));
	}

	private List<String> extractPlaceIds(MvcResult result) throws Exception {
		var items = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("items");
		List<String> ids = new java.util.ArrayList<>();
		items.forEach(item -> ids.add(item.path("placeId").asString()));
		return ids;
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder placeRequest() {
		return get("/places").header("Authorization", "Bearer " + member.accessToken());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder placeDetailRequest(
			UUID placeId) {
		return get("/places/" + placeId).header("Authorization", "Bearer " + member.accessToken());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder savedPlacesRequest() {
		return get("/members/me/saved-places").header("Authorization", "Bearer " + member.accessToken());
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

	private void savePlaceForMember(UUID memberId, UUID placeId) {
		jdbcTemplate.update("INSERT INTO saved_places (member_id, place_id) VALUES (?, ?)", memberId, placeId);
	}

	private void savePlaceForMemberAt(UUID memberId, UUID placeId, Instant savedAt) {
		jdbcTemplate.update("INSERT INTO saved_places (member_id, place_id, saved_at) VALUES (?, ?, ?)", memberId,
				placeId, Timestamp.from(savedAt));
	}

	private void startTrip(UUID memberId) {
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status) VALUES (?, ?, 'IN_PROGRESS')", UUID.randomUUID(),
				memberId);
	}

	private UUID savePlace(PlaceCategory category, String name, double latitude, double longitude) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO places (id, category, name, location) VALUES (?, ?::place_category, ?, "
						+ "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)",
				id, category.name(), name, longitude, latitude);
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
