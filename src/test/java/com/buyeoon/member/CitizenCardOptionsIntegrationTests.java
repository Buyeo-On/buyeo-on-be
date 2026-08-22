package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CitizenCardOptionsIntegrationTests {

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

	@BeforeEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
		jdbcTemplate.update("DELETE FROM card_characters");
		jdbcTemplate.update("DELETE FROM card_themes");
	}

	/** 승인된 캐릭터와 테마를 정해진 순서와 10분 유효 이미지 URL로 반환한다. */
	@Test
	@DisplayName("군민증 선택지는 표시 순서와 임시 이미지 URL을 포함한다")
	void optionsAreOrderedAndContainPresignedImageUrls() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertCharacter("둘째", "public/characters/second.webp", 2);
		insertCharacter("첫째", "public/characters/first.webp", 1);
		insertTheme("둘째 테마", "public/themes/second.webp", 2);
		insertTheme("첫째 테마", "public/themes/first.webp", 1);

		// 실제 인증 HTTP 응답의 순서와 S3 서명 만료 시간을 함께 검증한다.
		MvcResult result = mockMvc
				.perform(get("/citizen-cards/options").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.characters[0].name").value("첫째"))
				.andExpect(jsonPath("$.data.characters[1].name").value("둘째"))
				.andExpect(jsonPath("$.data.themes[0].name").value("첫째 테마"))
				.andExpect(jsonPath("$.data.themes[1].name").value("둘째 테마")).andReturn();

		JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
		assertPresignedUrl(data.get("characters").get(0).get("imageUrl").stringValue(),
				"/public/characters/first.webp");
		assertPresignedUrl(data.get("themes").get(0).get("imageUrl").stringValue(), "/public/themes/first.webp");
	}

	/** 선택지 API는 활성 인증 세션이 없으면 사용할 수 없다. */
	@Test
	@DisplayName("군민증 선택지 조회에는 인증이 필요하다")
	void authenticationIsRequired() throws Exception {
		// 인증 헤더 없는 요청은 보안 필터에서 401로 거부한다.
		mockMvc.perform(get("/citizen-cards/options")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 카탈로그 표시 순서는 양수이며 같은 카탈로그 안에서 중복될 수 없다. */
	@Test
	@DisplayName("카탈로그 표시 순서는 양수이고 중복될 수 없다")
	void sortOrderConstraintsAreEnforced() {
		insertCharacter("첫째", "public/characters/first.webp", 1);

		// 실제 PostgreSQL 제약이 중복과 0 이하 값을 거부하는지 확인한다.
		assertThatThrownBy(() -> insertCharacter("중복", "public/characters/duplicate.webp", 1))
				.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> insertTheme("잘못된 순서", "public/themes/invalid.webp", 0)).isInstanceOf(Exception.class);
	}

	private void assertPresignedUrl(String value, String expectedPath) {
		URI uri = URI.create(value);
		assertThat(uri.getHost()).isEqualTo("buyeoon-test-images.s3.ap-northeast-2.amazonaws.com");
		assertThat(uri.getPath()).isEqualTo(expectedPath);
		assertThat(uri.getQuery()).contains("X-Amz-Expires=600");
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
		return new AuthenticatedMember(accessTokenService.issue(memberId, sessionId));
	}

	private void insertCharacter(String name, String imageKey, int sortOrder) {
		jdbcTemplate.update("INSERT INTO card_characters (name, image_key, sort_order) VALUES (?, ?, ?)", name,
				imageKey, sortOrder);
	}

	private void insertTheme(String name, String imageKey, int sortOrder) {
		jdbcTemplate.update("INSERT INTO card_themes (name, image_key, sort_order) VALUES (?, ?, ?)", name, imageKey,
				sortOrder);
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
		registry.add("storage.images.bucket", () -> "buyeoon-test-images");
		registry.add("storage.images.region", () -> "ap-northeast-2");
	}

	private record AuthenticatedMember(String accessToken) {
	}
}
