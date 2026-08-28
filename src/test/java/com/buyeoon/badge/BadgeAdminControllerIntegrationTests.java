package com.buyeoon.badge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
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
class BadgeAdminControllerIntegrationTests {

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
				.thenReturn(Optional.of(new PublicImageObject("image/png", 100, "image/png", 100)));
	}

	@AfterAll
	static void clearAwsCredentials() {
		System.clearProperty("aws.accessKeyId");
		System.clearProperty("aws.secretAccessKey");
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM member_badges");
		jdbcTemplate.update("DELETE FROM badge_conditions WHERE badge_id IN (SELECT id FROM badges WHERE name LIKE '테스트%')");
		jdbcTemplate.update("DELETE FROM badges WHERE name LIKE '테스트%'");
	}

	@Test
	@DisplayName("조건을 포함해 생성하고 수정한 뒤 retire/activate 토글이 동작한다")
	void createUpdateAndToggleBadge() throws Exception {
		MvcResult createResult = mockMvc
				.perform(createRequest("""
						{"category":"EXPLORATION","name":"테스트 배지","description":"설명","imageKey":"public/badge.png",
						"conditionText":"조건 설명","active":true,
						"conditions":[{"metricKey":"MISSION_COMPLETED_COUNT","threshold":5}]}
						"""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.badgeId").exists()).andReturn();
		String badgeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data")
				.path("badgeId").asString();

		mockMvc.perform(getDetailRequest(badgeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.retired").value(false))
				.andExpect(jsonPath("$.data.conditions.length()").value(1));

		mockMvc.perform(updateRequest(badgeId, """
				{"category":"QUIZ","name":"테스트 배지 수정","description":"수정 설명","imageKey":"public/badge2.png",
				"conditionText":"수정 조건",
				"conditions":[{"metricKey":"QUIZ_CORRECT_COUNT","threshold":10}]}
				""")).andExpect(status().isOk());

		mockMvc.perform(getDetailRequest(badgeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("테스트 배지 수정"));

		mockMvc.perform(retireRequest(badgeId)).andExpect(status().isOk());
		mockMvc.perform(getDetailRequest(badgeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.retired").value(true));

		mockMvc.perform(activateRequest(badgeId)).andExpect(status().isOk());
		mockMvc.perform(getDetailRequest(badgeId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.retired").value(false));
	}

	@Test
	@DisplayName("잘못된 metricKey로 생성하면 400을 받는다")
	void returns400ForInvalidMetricKey() throws Exception {
		mockMvc.perform(createRequest("""
				{"category":"EXPLORATION","name":"테스트 배지","description":"설명","conditionText":"조건",
				"active":true,"conditions":[{"metricKey":"NOT_A_METRIC","threshold":1}]}
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("조건 없이 활성화를 시도하면 400을 받는다")
	void returns400WhenActivatingWithoutConditions() throws Exception {
		MvcResult createResult = mockMvc
				.perform(createRequest("""
						{"category":"EXPLORATION","name":"테스트 배지","description":"설명","conditionText":"조건",
						"active":false,"conditions":[]}
						"""))
				.andExpect(status().isOk()).andReturn();
		String badgeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data")
				.path("badgeId").asString();

		mockMvc.perform(activateRequest(badgeId)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("조건 없이 즉시 활성화로 생성하면 400을 받는다")
	void returns400WhenCreatingActiveWithoutConditions() throws Exception {
		mockMvc.perform(createRequest("""
				{"category":"EXPLORATION","name":"테스트 배지","description":"설명","conditionText":"조건",
				"active":true,"conditions":[]}
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("허용된 metric 목록을 조회할 수 있다")
	void returnsAllowedMetrics() throws Exception {
		mockMvc.perform(get("/admin/badge-metrics").header("X-Admin-Api-Key", VALID_API_KEY)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(8));
	}

	@Test
	@DisplayName("API Key가 없으면 401을 받는다")
	void returns401WhenApiKeyMissing() throws Exception {
		mockMvc.perform(get("/admin/badges")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("존재하지 않는 배지를 조회하면 404를 받는다")
	void returns404WhenBadgeDoesNotExist() throws Exception {
		mockMvc.perform(getDetailRequest(java.util.UUID.randomUUID().toString())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("목록 조회 시 각 배지의 조건이 함께 반환된다")
	void listIncludesConditionsForEachBadge() throws Exception {
		MvcResult createResult = mockMvc
				.perform(createRequest("""
						{"category":"EXPLORATION","name":"테스트 배지","description":"설명","conditionText":"조건 설명",
						"active":true,
						"conditions":[{"metricKey":"MISSION_COMPLETED_COUNT","threshold":5}]}
						"""))
				.andExpect(status().isOk()).andReturn();
		String badgeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data")
				.path("badgeId").asString();

		mockMvc.perform(listRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[?(@.badgeId=='" + badgeId + "')].conditions[0].metricKey")
						.value("MISSION_COMPLETED_COUNT"))
				.andExpect(jsonPath("$.data.items[?(@.badgeId=='" + badgeId + "')].conditions[0].threshold").value(5));
	}

	@Test
	@DisplayName("이미지 업로드 URL을 발급받는다")
	void issuesImageUploadUrl() throws Exception {
		when(publicImageUploadPresigner.presign(anyString(), anyString(), anyLong()))
				.thenReturn(new PublicImageUploadTarget("https://s3.example.com/upload",
						Map.of("Content-Type", "image/png"), Instant.now().plusSeconds(600)));

		MvcResult result = mockMvc
				.perform(post("/admin/badges/images/upload-url").header("X-Admin-Api-Key", VALID_API_KEY)
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"contentType":"image/png","fileSizeBytes":100}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.uploadUrl").value("https://s3.example.com/upload")).andReturn();
		String imageKey = objectMapper.readTree(result.getResponse().getContentAsString()).path("data")
				.path("imageKey").asString();
		assertThat(imageKey).startsWith("public/badges/");
	}

	@Test
	@DisplayName("S3에 존재하지 않는 imageKey로 생성하면 400을 받는다")
	void returns400WhenImageKeyDoesNotExistInS3() throws Exception {
		when(publicImageObjectStore.head("public/missing.png")).thenReturn(Optional.empty());

		mockMvc.perform(createRequest("""
				{"category":"EXPLORATION","name":"테스트 배지","description":"설명","imageKey":"public/missing.png",
				"conditionText":"조건","active":false,"conditions":[]}
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	private MockHttpServletRequestBuilder createRequest(String body) {
		return post("/admin/badges").header("X-Admin-Api-Key", VALID_API_KEY).contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private MockHttpServletRequestBuilder updateRequest(String badgeId, String body) {
		return put("/admin/badges/{badgeId}", badgeId).header("X-Admin-Api-Key", VALID_API_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private MockHttpServletRequestBuilder retireRequest(String badgeId) {
		return post("/admin/badges/{badgeId}/retire", badgeId).header("X-Admin-Api-Key", VALID_API_KEY);
	}

	private MockHttpServletRequestBuilder activateRequest(String badgeId) {
		return post("/admin/badges/{badgeId}/activate", badgeId).header("X-Admin-Api-Key", VALID_API_KEY);
	}

	private MockHttpServletRequestBuilder getDetailRequest(String badgeId) {
		return get("/admin/badges/{badgeId}", badgeId).header("X-Admin-Api-Key", VALID_API_KEY);
	}

	private MockHttpServletRequestBuilder listRequest() {
		return get("/admin/badges").header("X-Admin-Api-Key", VALID_API_KEY);
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
