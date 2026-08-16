package com.buyeoon.place;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.place.entity.PlaceCategory;
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

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder placeRequest() {
		return get("/places").header("Authorization", "Bearer " + member.accessToken());
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
