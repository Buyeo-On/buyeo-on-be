package com.buyeoon.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.common.storage.PublicImageObjectStore;
import com.buyeoon.common.storage.PublicImageObjectStore.PublicImageObject;
import com.buyeoon.common.storage.PublicImageUploadPresigner;
import com.buyeoon.common.storage.PublicImageUploadPresigner.PublicImageUploadTarget;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PlaceAdminControllerIntegrationTests {

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

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private PublicImageObjectStore publicImageObjectStore;

	@MockitoBean
	private PublicImageUploadPresigner publicImageUploadPresigner;

	@BeforeAll
	static void configureAwsCredentials() {
		System.setProperty("aws.accessKeyId", "test-access-key");
		System.setProperty("aws.secretAccessKey", "test-secret-key");
	}

	@BeforeEach
	void stubPublicImageObjectStore() {
		when(publicImageObjectStore.head(anyString()))
				.thenReturn(Optional.of(new PublicImageObject("image/jpeg", 100, "image/jpeg", 100)));
	}

	@AfterAll
	static void clearAwsCredentials() {
		System.clearProperty("aws.accessKeyId");
		System.clearProperty("aws.secretAccessKey");
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM saved_places");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
	}

	@Test
	@DisplayName("생성 후 수정, 목록/상세 조회, 삭제, 목록 제외까지 전체 흐름이 동작한다")
	void createUpdateListAndDeleteFlow() throws Exception {
		MvcResult createResult = mockMvc
				.perform(createRequest("""
						{"category":"HERITAGE","name":"관리자 생성 장소","summary":"요약","description":"설명",
						"address":"주소","imageKey":"public/place.jpg","latitude":-75.0,"longitude":0.0,
						"operatingHoursRaw":"09:00~18:00","alwaysOpen":false,"opensAt":"09:00","closesAt":"18:00",
						"admissionFee":1000}"""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.placeId").exists()).andReturn();
		String placeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data")
				.path("placeId").asString();

		mockMvc.perform(getDetailRequest(placeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("관리자 생성 장소"))
				.andExpect(jsonPath("$.data.deleted").value(false));

		mockMvc.perform(updateRequest(placeId, """
				{"category":"CAFE","name":"수정된 장소","summary":"새 요약","description":"새 설명",
				"address":"새 주소","imageKey":"public/new.jpg","latitude":-75.0,"longitude":0.0,
				"operatingHoursRaw":"상시 개방","alwaysOpen":true,"admissionFee":0}"""))
				.andExpect(status().isOk());

		mockMvc.perform(getDetailRequest(placeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("수정된 장소"))
				.andExpect(jsonPath("$.data.category").value("CAFE"));

		mockMvc.perform(listRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[?(@.placeId=='" + placeId + "')]").exists());

		mockMvc.perform(deleteRequest(placeId)).andExpect(status().isOk());

		mockMvc.perform(getDetailRequest(placeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.deleted").value(true));

		Boolean deleted = jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM places WHERE id = ?::uuid",
				Boolean.class, placeId);
		assertThat(deleted).isTrue();

		mockMvc.perform(restoreRequest(placeId)).andExpect(status().isOk());

		mockMvc.perform(getDetailRequest(placeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.deleted").value(false));
	}

	@Test
	@DisplayName("장소를 삭제하면 딸린 미션도 함께 삭제되고, 장소를 복구해도 미션은 그대로 삭제 상태다")
	void deletingPlaceCascadesToMissionsButRestoringPlaceDoesNot() throws Exception {
		MvcResult createResult = mockMvc
				.perform(createRequest("""
						{"category":"HERITAGE","name":"관리자 생성 장소2","summary":"요약","description":"설명",
						"address":"주소","latitude":-76.0,"longitude":0.0}"""))
				.andExpect(status().isOk()).andReturn();
		String placeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data")
				.path("placeId").asString();

		String missionId = jdbcTemplate.queryForObject("""
				INSERT INTO missions (place_id, type, title, description, reward_points, location)
				VALUES (?::uuid, 'PHOTO', '테스트 미션', '설명', 10, (SELECT location FROM places WHERE id = ?::uuid))
				RETURNING id
				""", String.class, placeId, placeId);

		mockMvc.perform(deleteRequest(placeId)).andExpect(status().isOk());

		Boolean missionDeleted = jdbcTemplate.queryForObject(
				"SELECT deleted_at IS NOT NULL FROM missions WHERE id = ?::uuid", Boolean.class, missionId);
		assertThat(missionDeleted).isTrue();

		mockMvc.perform(restoreRequest(placeId)).andExpect(status().isOk());

		Boolean stillDeleted = jdbcTemplate.queryForObject(
				"SELECT deleted_at IS NOT NULL FROM missions WHERE id = ?::uuid", Boolean.class, missionId);
		assertThat(stillDeleted).isTrue();

		jdbcTemplate.update("DELETE FROM missions WHERE id = ?::uuid", missionId);
	}

	@Test
	@DisplayName("API Key가 없으면 401을 받는다")
	void returns401WhenApiKeyMissing() throws Exception {
		mockMvc.perform(get("/admin/places")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("API Key가 틀리면 401을 받는다")
	void returns401WhenApiKeyIncorrect() throws Exception {
		mockMvc.perform(get("/admin/places").header("X-Admin-Api-Key", "wrong-key")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("필수 값이 없으면 400을 받는다")
	void returns400WhenRequiredFieldsMissing() throws Exception {
		mockMvc.perform(createRequest("{}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("존재하지 않는 장소를 조회하면 404를 받는다")
	void returns404WhenPlaceDoesNotExist() throws Exception {
		mockMvc.perform(getDetailRequest(UUID.randomUUID().toString())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("이미지 업로드 URL을 발급받는다")
	void issuesImageUploadUrl() throws Exception {
		when(publicImageUploadPresigner.presign(anyString(), anyString(), anyLong()))
				.thenReturn(new PublicImageUploadTarget("https://s3.example.com/upload",
						Map.of("Content-Type", "image/jpeg"), Instant.now().plusSeconds(600)));

		MvcResult result = mockMvc
				.perform(post("/admin/places/images/upload-url").header("X-Admin-Api-Key", VALID_API_KEY)
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"contentType":"image/jpeg","fileSizeBytes":100}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.uploadUrl").value("https://s3.example.com/upload")).andReturn();
		String imageKey = objectMapper.readTree(result.getResponse().getContentAsString()).path("data")
				.path("imageKey").asString();
		assertThat(imageKey).startsWith("public/places/");
	}

	@Test
	@DisplayName("S3에 존재하지 않는 imageKey로 생성하면 400을 받는다")
	void returns400WhenImageKeyDoesNotExistInS3() throws Exception {
		when(publicImageObjectStore.head("public/missing.jpg")).thenReturn(Optional.empty());

		mockMvc.perform(createRequest("""
				{"category":"HERITAGE","name":"장소","summary":"요약","description":"설명",
				"address":"주소","imageKey":"public/missing.jpg","latitude":-75.0,"longitude":0.0}"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("public/로 시작하지 않는 imageKey로 생성하면 400을 받는다")
	void returns400WhenImageKeyPrefixInvalid() throws Exception {
		mockMvc.perform(createRequest("""
				{"category":"HERITAGE","name":"장소","summary":"요약","description":"설명",
				"address":"주소","imageKey":"private/place.jpg","latitude":-75.0,"longitude":0.0}"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	private MockHttpServletRequestBuilder createRequest(String body) {
		return post("/admin/places").header("X-Admin-Api-Key", VALID_API_KEY).contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private MockHttpServletRequestBuilder updateRequest(String placeId, String body) {
		return put("/admin/places/{placeId}", placeId).header("X-Admin-Api-Key", VALID_API_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private MockHttpServletRequestBuilder deleteRequest(String placeId) {
		return delete("/admin/places/{placeId}", placeId).header("X-Admin-Api-Key", VALID_API_KEY);
	}

	private MockHttpServletRequestBuilder restoreRequest(String placeId) {
		return post("/admin/places/{placeId}/restore", placeId).header("X-Admin-Api-Key", VALID_API_KEY);
	}

	private MockHttpServletRequestBuilder getDetailRequest(String placeId) {
		return get("/admin/places/{placeId}", placeId).header("X-Admin-Api-Key", VALID_API_KEY);
	}

	private MockHttpServletRequestBuilder listRequest() {
		return get("/admin/places").header("X-Admin-Api-Key", VALID_API_KEY);
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
