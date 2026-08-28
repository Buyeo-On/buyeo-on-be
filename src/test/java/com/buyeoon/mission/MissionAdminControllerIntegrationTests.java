package com.buyeoon.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class MissionAdminControllerIntegrationTests {

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
		jdbcTemplate.update(
				"DELETE FROM mission_choices WHERE mission_id IN "
						+ "(SELECT id FROM missions WHERE place_id IN (SELECT id FROM places WHERE ST_Y(location::geometry) < -70))");
		jdbcTemplate.update(
				"DELETE FROM missions WHERE place_id IN (SELECT id FROM places WHERE ST_Y(location::geometry) < -70)");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
	}

	@Test
	@DisplayName("객관식 미션을 생성하면 choices가 저장되고, 수정하면 교체되며, 삭제하면 목록에서 제외된다")
	void createUpdateAndDeleteMultipleChoiceMission() throws Exception {
		UUID placeId = insertPlace();

		MvcResult createResult = mockMvc
				.perform(createRequest("""
						{"placeId":"%s","type":"MULTIPLE_CHOICE","title":"문제1","description":"설명",
						"rewardPoints":100,"maxAttempts":3,
						"choices":[{"label":"보기1","correct":true,"sortOrder":0},
						           {"label":"보기2","correct":false,"sortOrder":1}]}
						""".formatted(placeId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.missionId").exists()).andReturn();
		String missionId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data")
				.path("missionId").asString();

		mockMvc.perform(getDetailRequest(missionId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.type").value("MULTIPLE_CHOICE"))
				.andExpect(jsonPath("$.data.choices.length()").value(2))
				.andExpect(jsonPath("$.data.latitude").value(-75.0))
				.andExpect(jsonPath("$.data.longitude").value(0.0));

		mockMvc.perform(updateRequest(missionId, """
				{"title":"수정된 문제","description":"수정된 설명","rewardPoints":200,"maxAttempts":5,
				"choices":[{"label":"새 보기","correct":true,"sortOrder":0}],
				"latitude":-74.5,"longitude":1.0}
				""")).andExpect(status().isOk());

		mockMvc.perform(getDetailRequest(missionId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.title").value("수정된 문제"))
				.andExpect(jsonPath("$.data.choices.length()").value(1))
				.andExpect(jsonPath("$.data.latitude").value(-74.5))
				.andExpect(jsonPath("$.data.longitude").value(1.0));

		mockMvc.perform(listRequest(placeId.toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[?(@.missionId=='" + missionId + "')]").exists());

		mockMvc.perform(deleteRequest(missionId)).andExpect(status().isOk());

		mockMvc.perform(getDetailRequest(missionId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.deleted").value(true));

		Boolean deleted = jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM missions WHERE id = ?::uuid",
				Boolean.class, missionId);
		assertThat(deleted).isTrue();
	}

	@Test
	@DisplayName("PHOTO 타입 미션에 maxAttempts를 넣으면 400을 받는다")
	void returns400WhenPhotoMissionHasMaxAttempts() throws Exception {
		UUID placeId = insertPlace();

		mockMvc.perform(createRequest("""
				{"placeId":"%s","type":"PHOTO","title":"사진미션","description":"설명","rewardPoints":100,"maxAttempts":3}
				""".formatted(placeId))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("좌표를 지정해 생성하면 장소 좌표가 아니라 지정한 좌표가 저장된다")
	void createsMissionWithCustomLocation() throws Exception {
		UUID placeId = insertPlace();

		MvcResult createResult = mockMvc.perform(createRequest("""
				{"placeId":"%s","type":"OX","title":"OX 미션","description":"설명","rewardPoints":100,
				"oxCorrectAnswer":true,"latitude":-74.9,"longitude":0.5}
				""".formatted(placeId))).andExpect(status().isOk()).andReturn();
		String missionId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data")
				.path("missionId").asString();

		mockMvc.perform(getDetailRequest(missionId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.latitude").value(-74.9)).andExpect(jsonPath("$.data.longitude").value(0.5));
	}

	@Test
	@DisplayName("위도만 있고 경도가 없으면 400을 받는다")
	void returns400WhenOnlyLatitudeProvided() throws Exception {
		UUID placeId = insertPlace();

		mockMvc.perform(createRequest("""
				{"placeId":"%s","type":"OX","title":"OX 미션","description":"설명","rewardPoints":100,
				"oxCorrectAnswer":true,"latitude":-74.9}
				""".formatted(placeId))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("OX 타입 미션을 생성하고 조회할 수 있다")
	void createsOxMission() throws Exception {
		UUID placeId = insertPlace();

		mockMvc.perform(createRequest("""
				{"placeId":"%s","type":"OX","title":"OX 미션","description":"설명","rewardPoints":100,
				"oxCorrectAnswer":true}
				""".formatted(placeId))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("API Key가 없으면 401을 받는다")
	void returns401WhenApiKeyMissing() throws Exception {
		mockMvc.perform(get("/admin/missions")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("API Key가 틀리면 401을 받는다")
	void returns401WhenApiKeyIncorrect() throws Exception {
		mockMvc.perform(get("/admin/missions").header("X-Admin-Api-Key", "wrong-key"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("존재하지 않는 미션을 조회하면 404를 받는다")
	void returns404WhenMissionDoesNotExist() throws Exception {
		mockMvc.perform(getDetailRequest(UUID.randomUUID().toString())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("삭제한 미션을 복구하면 다시 활성 상태가 된다")
	void restoresDeletedMission() throws Exception {
		UUID placeId = insertPlace();

		MvcResult createResult = mockMvc.perform(createRequest("""
				{"placeId":"%s","type":"OX","title":"OX 미션","description":"설명","rewardPoints":100,
				"oxCorrectAnswer":true}
				""".formatted(placeId))).andExpect(status().isOk()).andReturn();
		String missionId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data")
				.path("missionId").asString();

		mockMvc.perform(deleteRequest(missionId)).andExpect(status().isOk());
		mockMvc.perform(restoreRequest(missionId)).andExpect(status().isOk());

		mockMvc.perform(getDetailRequest(missionId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.deleted").value(false));
	}

	@Test
	@DisplayName("소속 장소가 삭제된 상태면 미션을 복구할 수 없다")
	void returns400WhenRestoringMissionWithDeletedPlace() throws Exception {
		UUID placeId = insertPlace();

		MvcResult createResult = mockMvc.perform(createRequest("""
				{"placeId":"%s","type":"OX","title":"OX 미션","description":"설명","rewardPoints":100,
				"oxCorrectAnswer":true}
				""".formatted(placeId))).andExpect(status().isOk()).andReturn();
		String missionId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data")
				.path("missionId").asString();

		mockMvc.perform(deleteRequest(missionId)).andExpect(status().isOk());
		jdbcTemplate.update("UPDATE places SET deleted_at = now() WHERE id = ?", placeId);

		mockMvc.perform(restoreRequest(missionId)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	private UUID insertPlace() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO places (id, category, name, location) VALUES (?, 'HERITAGE'::place_category, '장소', "
						+ "ST_SetSRID(ST_MakePoint(0, -75), 4326)::geography)",
				id);
		return id;
	}

	private MockHttpServletRequestBuilder createRequest(String body) {
		return post("/admin/missions").header("X-Admin-Api-Key", VALID_API_KEY).contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private MockHttpServletRequestBuilder updateRequest(String missionId, String body) {
		return put("/admin/missions/{missionId}", missionId).header("X-Admin-Api-Key", VALID_API_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private MockHttpServletRequestBuilder deleteRequest(String missionId) {
		return delete("/admin/missions/{missionId}", missionId).header("X-Admin-Api-Key", VALID_API_KEY);
	}

	private MockHttpServletRequestBuilder getDetailRequest(String missionId) {
		return get("/admin/missions/{missionId}", missionId).header("X-Admin-Api-Key", VALID_API_KEY);
	}

	private MockHttpServletRequestBuilder listRequest(String placeId) {
		return get("/admin/missions").param("placeId", placeId).header("X-Admin-Api-Key", VALID_API_KEY);
	}

	private MockHttpServletRequestBuilder restoreRequest(String missionId) {
		return post("/admin/missions/{missionId}/restore", missionId).header("X-Admin-Api-Key", VALID_API_KEY);
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
