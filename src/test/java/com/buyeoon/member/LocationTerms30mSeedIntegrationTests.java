package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
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

/**
 * Flyway가 시드한 LOCATION 약관이 참여 거리 30m 버전을 현재 약관으로 노출하고, 이전 버전 동의만으로는 필수 약관이 충족되지
 * 않는지 공개 API에서 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LocationTerms30mSeedIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	private static final UUID PREVIOUS_LOCATION_TERM_ID = UUID.fromString("30000000-0000-4000-8000-000000000003");

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

	/** 시드된 약관 행은 유지하고 회원·동의 상태만 비운다. */
	@BeforeEach
	void cleanUpMemberState() {
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM term_consents");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/**
	 * GET /terms의 현재 LOCATION 본문은 30m를 말하고, V16의 100m 이전 버전 행은 목록에 나오지 않고 DB에 남는다.
	 */
	@Test
	@DisplayName("현재 LOCATION 약관은 참여 거리 30m이고 이전 버전 행은 유지된다")
	void currentLocationTermDescribesThirtyMeterParticipationDistance() throws Exception {
		mockMvc.perform(get("/terms")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(4))
				.andExpect(jsonPath("$.data.items[2].type").value("LOCATION"))
				.andExpect(jsonPath("$.data.items[2].termId").value(not(PREVIOUS_LOCATION_TERM_ID.toString())))
				.andExpect(jsonPath("$.data.items[2].content", containsString("30m")))
				.andExpect(jsonPath("$.data.items[2].content", not(containsString("100m"))))
				.andExpect(jsonPath("$.data.items[?(@.termId == '%s')]", PREVIOUS_LOCATION_TERM_ID).isEmpty());

		assertThat(jdbcTemplate.queryForObject("""
				SELECT content
				FROM terms
				WHERE id = ?
				""", String.class, PREVIOUS_LOCATION_TERM_ID)).contains("100m 이내");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM terms WHERE type = 'LOCATION'", Long.class))
				.isEqualTo(2L);
	}

	/**
	 * 이전 LOCATION에만 동의한 회원은 필수 약관 미동의이고, 현재 약관 전체에 다시 동의하면 동의 완료가 된다.
	 */
	@Test
	@DisplayName("이전 LOCATION 동의만으로는 재동의 전까지 필수 약관 미동의다")
	void previousLocationConsentRequiresCurrentVersionAgain() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		consentToDraftTermsExceptCurrentLocation(member.memberId());

		mockMvc.perform(get("/members/me").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.requiredTermsAgreed").value(false));

		MvcResult termsResponse = mockMvc.perform(get("/terms")).andExpect(status().isOk()).andReturn();
		JsonNode items = objectMapper.readTree(termsResponse.getResponse().getContentAsString()).get("data")
				.get("items");
		mockMvc.perform(put("/members/me/term-consents").header("Authorization", "Bearer " + member.accessToken())
				.header("Idempotency-Key", "location-30m-reconsent").contentType(MediaType.APPLICATION_JSON)
				.content(consentRequest(items))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.requiredTermsAgreed").value(true));

		mockMvc.perform(get("/members/me").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.requiredTermsAgreed").value(true));
	}

	/** 시드된 LOCATION 버전들은 시행 시각이 다르며 같은 시행 시각을 다시 넣을 수 없다. */
	@Test
	@DisplayName("LOCATION 약관은 같은 시행 시각을 중복할 수 없다")
	void locationTermsCannotShareEffectiveAt() {
		Timestamp currentEffectiveAt = jdbcTemplate.queryForObject("""
				SELECT effective_at
				FROM terms
				WHERE type = 'LOCATION'
				ORDER BY effective_at DESC
				LIMIT 1
				""", Timestamp.class);
		Timestamp previousEffectiveAt = jdbcTemplate.queryForObject("""
				SELECT effective_at
				FROM terms
				WHERE id = ?
				""", Timestamp.class, PREVIOUS_LOCATION_TERM_ID);

		assertThat(currentEffectiveAt).isNotEqualTo(previousEffectiveAt);
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO terms (id, type, version, required, title, content, effective_at)
				VALUES (?, 'LOCATION'::term_type, 'duplicate-effective-at', true, '중복', '본문', ?)
				""", UUID.randomUUID(), currentEffectiveAt)).isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 현재 약관 목록을 PUT /members/me/term-consents 본문으로 변환한다. */
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

	/** V16 초안 약관에만 동의해 현재 LOCATION 버전 동의는 비워 둔다. */
	private void consentToDraftTermsExceptCurrentLocation(UUID memberId) {
		jdbcTemplate.update("""
				INSERT INTO term_consents (member_id, term_id, agreed)
				SELECT ?, id, true
				FROM terms
				WHERE version = '0.1-draft'
				""", memberId);
	}

	/** 활성 회원과 유효 세션을 만들어 액세스 토큰을 발급한다. */
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

	/** 테스트 DB를 Testcontainers PostGIS와 Flyway로 연결한다. */
	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> APPLICATION_USERNAME);
		registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	/** 인증된 테스트 회원과 액세스 토큰을 묶는다. */
	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
