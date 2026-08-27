package com.buyeoon.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.place.sync.TourApiAreaItem;
import com.buyeoon.place.sync.TourApiClient;
import com.buyeoon.place.sync.TourApiPlaceDetail;
import java.sql.Time;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PlaceSyncControllerIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	private static final String VALID_API_KEY = "test-admin-api-key";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private TourApiClient tourApiClient;

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

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM places WHERE source_name = 'TOUR_API'");
	}

	/** 신규 contentId는 새 장소로 삽입되고, 관람시간 파싱에 성공하면 구조화 필드가 채워진다. */
	@Test
	@DisplayName("유효한 API Key로 호출하면 신규 장소가 삽입되고 관람시간이 파싱된다")
	void syncsNewPlaceWithParsedOperatingHours() throws Exception {
		TourApiAreaItem item = new TourApiAreaItem("1001", "12", null);
		given(tourApiClient.fetchAreaItems()).willReturn(List.of(item));
		given(tourApiClient.fetchPlaceDetail(item))
				.willReturn(new TourApiPlaceDetail("1001", "부소산성", "백제의 마지막 왕성", "충남 부여군 부여읍",
						"https://tourapi.example.com/image.jpg", 36.2754, 126.9098, "09:00~18:00", "무료", Map.of()));

		mockMvc.perform(syncRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.successCount").value(1))
				.andExpect(jsonPath("$.data.failureCount").value(0));

		Map<String, Object> row = jdbcTemplate
				.queryForMap("SELECT name, summary, category::text, opens_at, closes_at, always_open, admission_fee, "
						+ "operating_hours_raw, source_image_href "
						+ "FROM places WHERE source_name = 'TOUR_API' AND external_id = '1001'");
		assertThat(row.get("name")).isEqualTo("부소산성");
		assertThat(row.get("summary")).isEqualTo("백제의 마지막 왕성");
		assertThat(row.get("category")).isEqualTo("HERITAGE");
		assertThat(row.get("opens_at")).isEqualTo(Time.valueOf(LocalTime.of(9, 0)));
		assertThat(row.get("closes_at")).isEqualTo(Time.valueOf(LocalTime.of(18, 0)));
		assertThat(row.get("always_open")).isEqualTo(false);
		assertThat(row.get("admission_fee")).isEqualTo(0);
		assertThat(row.get("operating_hours_raw")).isEqualTo("09:00~18:00");
		assertThat(row.get("source_image_href")).isEqualTo("https://tourapi.example.com/image.jpg");
	}

	@Test
	@DisplayName("상시 개방과 무료 입장 정보는 구조화 필드로 저장된다")
	void syncsAlwaysOpenAndFreeAdmission() throws Exception {
		TourApiAreaItem item = new TourApiAreaItem("1002", "12", null);
		given(tourApiClient.fetchAreaItems()).willReturn(List.of(item));
		given(tourApiClient.fetchPlaceDetail(item)).willReturn(
				new TourApiPlaceDetail("1002", "정림사지", "백제 문화의 정수, 정림사지 오층석탑이 있는 절터예요. 국보 제9호로 사비 백제를 대표해요.", "충남 부여군",
						null, 36.2, 126.9, "상시 개방", "입장료 없음", Map.of()));

		mockMvc.perform(syncRequest()).andExpect(status().isOk());

		Map<String, Object> row = jdbcTemplate
				.queryForMap("SELECT summary, always_open, opens_at, closes_at, admission_fee FROM places "
						+ "WHERE source_name = 'TOUR_API' AND external_id = '1002'");
		assertThat(row.get("summary")).isEqualTo("백제 문화의 정수, 정림사지 오층석탑이 있는 절터예요");
		assertThat(row.get("always_open")).isEqualTo(true);
		assertThat(row.get("opens_at")).isNull();
		assertThat(row.get("closes_at")).isNull();
		assertThat(row.get("admission_fee")).isEqualTo(0);
	}

	/** 기존 external_id를 가진 장소는 새로 삽입되지 않고 모든 필드가 최신값으로 덮어써진다. */
	@Test
	@DisplayName("기존 장소는 갱신되고 새로 삽입되지 않는다")
	void updatesExistingPlaceInstial() throws Exception {
		jdbcTemplate.update("""
				INSERT INTO places (id, category, name, address, location, source_name, external_id)
				VALUES (gen_random_uuid(), 'HERITAGE', '옛 이름', '옛 주소',
				        ST_SetSRID(ST_MakePoint(126.0, 36.0), 4326)::geography, 'TOUR_API', '2002')
				""");

		TourApiAreaItem item = new TourApiAreaItem("2002", "12", null);
		given(tourApiClient.fetchAreaItems()).willReturn(List.of(item));
		given(tourApiClient.fetchPlaceDetail(item)).willReturn(
				new TourApiPlaceDetail("2002", "새 이름", "새 설명", "새 주소", null, 36.5, 126.5, null, null, Map.of()));

		mockMvc.perform(syncRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.data.successCount").value(1));

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM places WHERE source_name = 'TOUR_API' AND external_id = '2002'", Integer.class);
		assertThat(count).isEqualTo(1);
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT name, address FROM places WHERE source_name = 'TOUR_API' AND external_id = '2002'");
		assertThat(row.get("name")).isEqualTo("새 이름");
		assertThat(row.get("address")).isEqualTo("새 주소");
	}

	/** 관람시간 파싱에 실패해도 원문은 저장되고 나머지 필드는 정상 upsert된다. */
	@Test
	@DisplayName("관람시간 파싱 실패 시 원문은 저장되고 구조화 필드는 비어 있다")
	void keepsRawTextWhenOperatingHoursParsingFails() throws Exception {
		TourApiAreaItem item = new TourApiAreaItem("3003", "14", null);
		given(tourApiClient.fetchAreaItems()).willReturn(List.of(item));
		given(tourApiClient.fetchPlaceDetail(item)).willReturn(new TourApiPlaceDetail("3003", "박물관", "설명", "주소", null,
				36.3, 126.3, "매주 월요일 휴관, 문의 요망", null, Map.of()));

		mockMvc.perform(syncRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.data.successCount").value(1));

		Map<String, Object> row = jdbcTemplate
				.queryForMap("SELECT opens_at, closes_at, operating_hours_raw FROM places "
						+ "WHERE source_name = 'TOUR_API' AND external_id = '3003'");
		assertThat(row.get("opens_at")).isNull();
		assertThat(row.get("closes_at")).isNull();
		assertThat(row.get("operating_hours_raw")).isEqualTo("매주 월요일 휴관, 문의 요망");
	}

	/** detailInfo2 이용안내가 jsonb 컬럼에 항목명 -> 내용으로 저장된다. */
	@Test
	@DisplayName("detailInfo2 이용안내가 detail_info에 저장된다")
	void storesDetailInfo() throws Exception {
		TourApiAreaItem item = new TourApiAreaItem("5005", "12", null);
		given(tourApiClient.fetchAreaItems()).willReturn(List.of(item));
		given(tourApiClient.fetchPlaceDetail(item)).willReturn(
				new TourApiPlaceDetail("5005", "금강 문화관", "설명", "주소", null, 36.4, 126.4, null, null, Map.of()));
		given(tourApiClient.fetchPlaceInfo(item)).willReturn(new LinkedHashMap<>(Map.of("입장료", "무료", "화장실", "있음")));

		mockMvc.perform(syncRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.data.successCount").value(1));

		String detailInfo = jdbcTemplate.queryForObject(
				"SELECT detail_info::text FROM places " + "WHERE source_name = 'TOUR_API' AND external_id = '5005'",
				String.class);
		assertThat(detailInfo).contains("\"입장료\": \"무료\"").contains("\"화장실\": \"있음\"");
	}

	/** 이용안내가 없는 장소는 빈 jsonb 객체로 남고 나머지 필드는 정상 저장된다. */
	@Test
	@DisplayName("이용안내가 없으면 detail_info는 빈 객체로 남는다")
	void storesEmptyDetailInfoWhenAbsent() throws Exception {
		TourApiAreaItem item = new TourApiAreaItem("5006", "39", null);
		given(tourApiClient.fetchAreaItems()).willReturn(List.of(item));
		given(tourApiClient.fetchPlaceDetail(item))
				.willReturn(new TourApiPlaceDetail("5006", "식당", "설명", "주소", null, 36.5, 126.5, null, null, Map.of()));
		given(tourApiClient.fetchPlaceInfo(item)).willReturn(Map.of());

		mockMvc.perform(syncRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.data.successCount").value(1));

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, detail_info::text AS detail_info FROM places "
				+ "WHERE source_name = 'TOUR_API' AND external_id = '5006'");
		assertThat(row.get("name")).isEqualTo("식당");
		assertThat(row.get("detail_info")).isEqualTo("{}");
	}

	/** 재동기화하면 이용안내는 병합하지 않고 최신 응답으로 통째로 교체된다. */
	@Test
	@DisplayName("재동기화 시 이용안내는 최신 응답으로 교체된다")
	void replacesDetailInfoOnResync() throws Exception {
		TourApiAreaItem item = new TourApiAreaItem("5007", "12", null);
		given(tourApiClient.fetchAreaItems()).willReturn(List.of(item));
		given(tourApiClient.fetchPlaceDetail(item))
				.willReturn(new TourApiPlaceDetail("5007", "박물관", "설명", "주소", null, 36.6, 126.6, null, null, Map.of()));
		given(tourApiClient.fetchPlaceInfo(item)).willReturn(Map.of("입장료", "무료", "등산로", "있음"));
		mockMvc.perform(syncRequest()).andExpect(status().isOk());

		given(tourApiClient.fetchPlaceInfo(item)).willReturn(Map.of("입장료", "2,000원"));
		mockMvc.perform(syncRequest()).andExpect(status().isOk());

		String detailInfo = jdbcTemplate.queryForObject(
				"SELECT detail_info::text FROM places " + "WHERE source_name = 'TOUR_API' AND external_id = '5007'",
				String.class);
		assertThat(detailInfo).contains("2,000원").doesNotContain("등산로");
	}

	/** 특정 항목 호출이 실패해도 나머지 항목은 계속 진행되고 실패한 contentId가 응답에 포함된다. */
	@Test
	@DisplayName("일부 항목이 실패해도 나머지는 계속 진행되고 실패 목록에 포함된다")
	void continuesAfterPartialFailureAndReportsFailedContentIds() throws Exception {
		TourApiAreaItem okItem = new TourApiAreaItem("4004", "12", null);
		TourApiAreaItem failingItem = new TourApiAreaItem("4005", "12", null);
		given(tourApiClient.fetchAreaItems()).willReturn(List.of(okItem, failingItem));
		given(tourApiClient.fetchPlaceDetail(okItem)).willReturn(
				new TourApiPlaceDetail("4004", "정림사지", "설명", "주소", null, 36.1, 126.1, null, null, Map.of()));
		willThrow(new IllegalStateException("TourAPI 호출 실패")).given(tourApiClient).fetchPlaceDetail(failingItem);

		mockMvc.perform(syncRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.data.successCount").value(1))
				.andExpect(jsonPath("$.data.failureCount").value(1))
				.andExpect(jsonPath("$.data.failedContentIds[0]").value("4005"));

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM places WHERE source_name = 'TOUR_API' AND external_id = '4004'", Integer.class);
		assertThat(count).isEqualTo(1);
	}

	/** API Key가 없으면 401을 반환한다. */
	@Test
	@DisplayName("API Key가 없으면 401을 받는다")
	void returns401WhenApiKeyMissing() throws Exception {
		mockMvc.perform(post("/admin/places/sync")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** API Key가 틀리면 401을 반환한다. */
	@Test
	@DisplayName("API Key가 틀리면 401을 받는다")
	void returns401WhenApiKeyIncorrect() throws Exception {
		mockMvc.perform(post("/admin/places/sync").header("X-Admin-Api-Key", "wrong-key"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder syncRequest() {
		return post("/admin/places/sync").header("X-Admin-Api-Key", VALID_API_KEY);
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
		registry.add("admin.api-key", () -> VALID_API_KEY);
	}
}
