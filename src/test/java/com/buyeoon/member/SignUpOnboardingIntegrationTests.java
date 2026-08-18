package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.social.AppleSocialCredential;
import com.buyeoon.member.auth.social.KakaoSocialCredential;
import com.buyeoon.member.auth.social.SocialCredentialVerifier;
import com.buyeoon.member.auth.social.VerifiedSocialIdentity;
import com.buyeoon.member.entity.SocialProvider;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SignUpOnboardingIntegrationTests {

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
	private ObjectMapper objectMapper;

	@MockitoBean(name = "kakaoSocialCredentialVerifier")
	private SocialCredentialVerifier kakaoVerifier;

	@MockitoBean(name = "appleSocialCredentialVerifier")
	private SocialCredentialVerifier appleVerifier;

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
	void setUpSocialVerifiers() {
		given(kakaoVerifier.provider()).willReturn(SocialProvider.KAKAO);
		given(kakaoVerifier.verify(any(KakaoSocialCredential.class)))
				.willReturn(new VerifiedSocialIdentity(SocialProvider.KAKAO, "uc02-new-member"));
		given(appleVerifier.provider()).willReturn(SocialProvider.APPLE);
		given(appleVerifier.verify(any(AppleSocialCredential.class)))
				.willReturn(new VerifiedSocialIdentity(SocialProvider.APPLE, "uc02-apple-member"));
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM citizen_cards");
		jdbcTemplate.update("DELETE FROM member_profiles");
		jdbcTemplate.update("DELETE FROM term_consents");
		jdbcTemplate.update("DELETE FROM terms");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM social_accounts");
		jdbcTemplate.update("DELETE FROM members");
		jdbcTemplate.update("DELETE FROM card_characters");
		jdbcTemplate.update("DELETE FROM card_themes");
	}

	/** UC-01, UC-03, UC-04 공개 API를 연결해 신규 회원의 온보딩 완료 상태까지 검증한다. */
	@Test
	@DisplayName("신규 소셜 회원은 약관 동의와 군민증 발급으로 온보딩을 완료한다")
	void newSocialMemberCompletesTermsAndCitizenCardOnboarding() throws Exception {
		insertCurrentTerms();
		Catalog catalog = insertCatalog();

		MvcResult login = mockMvc
				.perform(post("/auth/social-login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"provider\":\"KAKAO\",\"accessToken\":\"provider-token\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.isNewMember").value(true))
				.andExpect(jsonPath("$.data.member.requiredTermsAgreed").value(false))
				.andExpect(jsonPath("$.data.member.citizenCardIssued").value(false)).andReturn();
		JsonNode loginData = responseData(login);
		String accessToken = loginData.get("accessToken").stringValue();
		UUID memberId = UUID.fromString(loginData.get("member").get("memberId").stringValue());

		MvcResult termsResponse = mockMvc.perform(get("/terms")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(4)).andReturn();
		String consentRequest = consentRequest(responseData(termsResponse).get("items"));
		mockMvc.perform(put("/members/me/term-consents").header("Authorization", bearer(accessToken))
				.header("Idempotency-Key", "uc02-terms-key").contentType(MediaType.APPLICATION_JSON)
				.content(consentRequest)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.requiredTermsAgreed").value(true));

		mockMvc.perform(get("/citizen-cards/options").header("Authorization", bearer(accessToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.characters[0].id").value(catalog.characterId().toString()))
				.andExpect(jsonPath("$.data.themes[0].id").value(catalog.themeId().toString()));
		mockMvc.perform(post("/citizen-cards").header("Authorization", bearer(accessToken))
				.header("Idempotency-Key", "uc02-card-key-1").contentType(MediaType.APPLICATION_JSON)
				.content(cardRequest(catalog))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.displayName").value("부여새내기"));

		mockMvc.perform(get("/members/me").header("Authorization", bearer(accessToken))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.memberId").value(memberId.toString()))
				.andExpect(jsonPath("$.data.displayName").value("부여새내기"))
				.andExpect(jsonPath("$.data.characterId").value(catalog.characterId().toString()))
				.andExpect(jsonPath("$.data.requiredTermsAgreed").value(true))
				.andExpect(jsonPath("$.data.citizenCardIssued").value(true));

		assertThat(count("members")).isEqualTo(1);
		assertThat(count("social_accounts")).isEqualTo(1);
		assertThat(count("member_settings")).isEqualTo(1);
		assertThat(count("auth_sessions")).isEqualTo(1);
		assertThat(count("term_consents")).isEqualTo(4);
		assertThat(count("member_profiles")).isEqualTo(1);
		assertThat(count("citizen_cards")).isEqualTo(1);
		assertThat(count("idempotency_requests")).isEqualTo(2);
	}

	private JsonNode responseData(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String bearer(String accessToken) {
		return "Bearer " + accessToken;
	}

	private String consentRequest(JsonNode items) {
		StringBuilder body = new StringBuilder("{\"consents\":[");
		for (int index = 0; index < items.size(); index++) {
			if (index > 0) {
				body.append(',');
			}
			JsonNode item = items.get(index);
			body.append("{\"termId\":\"").append(item.get("termId").stringValue()).append("\",\"version\":\"")
					.append(item.get("version").stringValue()).append("\",\"agreed\":")
					.append(item.get("required").booleanValue()).append('}');
		}
		return body.append("]}").toString();
	}

	private String cardRequest(Catalog catalog) {
		return "{\"displayName\":\"부여새내기\",\"characterId\":\"" + catalog.characterId() + "\",\"themeId\":\""
				+ catalog.themeId() + "\",\"location\":{\"latitude\":36.27,\"longitude\":126.91,"
				+ "\"accuracyMeters\":5.0,\"capturedAt\":\"2026-08-12T15:30:00+09:00\"}}";
	}

	private void insertCurrentTerms() {
		Instant effectiveAt = Instant.parse("2026-08-01T00:00:00Z");
		insertTerm("SERVICE", true, effectiveAt);
		insertTerm("PRIVACY", true, effectiveAt);
		insertTerm("LOCATION", true, effectiveAt);
		insertTerm("MARKETING", false, effectiveAt);
	}

	private void insertTerm(String type, boolean required, Instant effectiveAt) {
		jdbcTemplate.update("""
				INSERT INTO terms (type, version, required, title, content, effective_at)
				VALUES (?::term_type, '1.0', ?, '테스트 약관', '테스트 본문', ?)
				""", type, required, Timestamp.from(effectiveAt));
	}

	private Catalog insertCatalog() {
		UUID characterId = UUID.randomUUID();
		UUID themeId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO card_characters (id, name, image_key, sort_order)
				VALUES (?, '금동이', 'public/characters/geumdong.webp', 1)
				""", characterId);
		jdbcTemplate.update("""
				INSERT INTO card_themes (id, name, image_key, sort_order)
				VALUES (?, '백제', 'public/themes/baekje.webp', 1)
				""", themeId);
		return new Catalog(characterId, themeId);
	}

	private long count(String table) {
		return Objects.requireNonNull(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class));
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
		registry.add("location.buyeo-boundary", () -> "classpath:boundaries/buyeo-test.geojson");
	}

	private record Catalog(UUID characterId, UUID themeId) {
	}
}
