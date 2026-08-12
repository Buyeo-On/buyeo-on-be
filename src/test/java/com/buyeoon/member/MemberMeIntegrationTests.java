package com.buyeoon.member;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MemberMeIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	private static final String JWT_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

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
	private JwtEncoder jwtEncoder;

	@Autowired
	private JwtDecoder jwtDecoder;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM citizen_cards");
		jdbcTemplate.update("DELETE FROM member_profiles");
		jdbcTemplate.update("DELETE FROM term_consents");
		jdbcTemplate.update("DELETE FROM terms");
		jdbcTemplate.update("DELETE FROM card_characters");
		jdbcTemplate.update("DELETE FROM card_themes");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/**
	 * 프로필을 생성하지 않은 ACTIVE 회원이 GET /members/me를 호출하면 displayName, characterId는
	 * null이고 requiredTermsAgreed, citizenCardIssued는 false로 반환된다.
	 */
	@Test
	@DisplayName("프로필이 없는 활성 회원은 초기 온보딩 상태를 반환한다")
	void activeMemberWithoutProfileGetsInitialOnboardingState() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		Instant createdAt = Instant.parse("2026-08-11T03:00:00Z");
		insertMember(memberId, "ACTIVE", createdAt);
		insertSession(sessionId, memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);

		mockMvc.perform(
				get("/members/me").header("Authorization", "Bearer " + accessTokenService.issue(memberId, sessionId)))
				.andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.memberId").value(memberId.toString()))
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.displayName").value((Object) null))
				.andExpect(jsonPath("$.data.characterId").value((Object) null))
				.andExpect(jsonPath("$.data.requiredTermsAgreed").value(false))
				.andExpect(jsonPath("$.data.citizenCardIssued").value(false))
				.andExpect(jsonPath("$.data.createdAt").value("2026-08-11T12:00:00+09:00"));
	}

	/**
	 * 프로필, 약관 동의, 군민증이 모두 설정된 회원이 GET /members/me를 호출하면 displayName, characterId,
	 * requiredTermsAgreed, citizenCardIssued가 모두 최신 상태로 반환된다.
	 */
	@Test
	@DisplayName("최신 프로필·약관·군민증 상태를 반환한다")
	void latestProfileTermsAndCitizenCardStateIsReturned() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		UUID characterId = UUID.randomUUID();
		UUID themeId = UUID.randomUUID();
		insertMember(memberId, "ACTIVE", Instant.parse("2026-08-01T00:00:00Z"));
		insertSession(sessionId, memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);
		jdbcTemplate.update("""
				INSERT INTO card_characters (id, name, image_key, sort_order)
				VALUES (?, '금동이', 'public/characters/geumdong.webp', 1)
				""", characterId);
		jdbcTemplate.update("""
				INSERT INTO card_themes (id, name, image_key, sort_order)
				VALUES (?, '백제', 'public/themes/baekje.webp', 1)
				""", themeId);
		jdbcTemplate.update(
				"INSERT INTO member_profiles (member_id, display_name, character_id) VALUES (?, '부여여행자', ?)", memberId,
				characterId);
		jdbcTemplate.update("INSERT INTO citizen_cards (member_id, theme_id, barcode_value) VALUES (?, ?, ?)", memberId,
				themeId, UUID.randomUUID().toString());
		UUID oldServiceTermId = insertTerm("SERVICE", "1.0", Instant.parse("2026-01-01T00:00:00Z"));
		UUID currentServiceTermId = insertTerm("SERVICE", "2.0", Instant.parse("2026-08-01T00:00:00Z"));
		UUID privacyTermId = insertTerm("PRIVACY", "1.0", Instant.parse("2026-01-01T00:00:00Z"));
		insertConsent(memberId, oldServiceTermId, false);
		insertConsent(memberId, currentServiceTermId, true);
		insertConsent(memberId, privacyTermId, true);

		mockMvc.perform(
				get("/members/me").header("Authorization", "Bearer " + accessTokenService.issue(memberId, sessionId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("부여여행자"))
				.andExpect(jsonPath("$.data.characterId").value(characterId.toString()))
				.andExpect(jsonPath("$.data.requiredTermsAgreed").value(true))
				.andExpect(jsonPath("$.data.citizenCardIssued").value(true));
	}

	/**
	 * 발급된 액세스 토큰을 JWT 디코딩하면 subject에 memberId, sid 클레임에 sessionId가 포함되고 만료 시간이 정확히
	 * 1시간이다.
	 */
	@Test
	@DisplayName("액세스 토큰은 회원 ID·세션 ID·1시간 만료를 포함한다")
	void accessTokenContainsMemberSessionAndOneHourLifetime() {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		insertMember(memberId, "ACTIVE", Instant.now());
		insertSession(sessionId, memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);

		Jwt jwt = jwtDecoder.decode(accessTokenService.issue(memberId, sessionId));

		org.assertj.core.api.Assertions.assertThat(jwt.getSubject()).isEqualTo(memberId.toString());
		org.assertj.core.api.Assertions.assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
		org.assertj.core.api.Assertions.assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
				.isEqualTo(Duration.ofHours(1));
	}

	/**
	 * Authorization 헤더가 없거나, JWT 형식이 아니거나, 서명이 변조되었거나, 만료된 액세스 토큰은 모두 401로 거부된다.
	 */
	@Test
	@DisplayName("없음·형식 오류·서명 불일치·만료된 액세스 토큰은 거부된다")
	void missingMalformedInvalidSignatureAndExpiredTokensAreRejected() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		insertMember(memberId, "ACTIVE", Instant.now());
		insertSession(sessionId, memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);
		String validToken = accessTokenService.issue(memberId, sessionId);
		int signatureStart = validToken.lastIndexOf('.') + 1;
		char firstSignatureCharacter = validToken.charAt(signatureStart);
		String changedSignature = validToken.substring(0, signatureStart) + (firstSignatureCharacter == 'A' ? 'B' : 'A')
				+ validToken.substring(signatureStart + 1);

		assertUnauthorized(null);
		assertUnauthorized("not-a-jwt");
		assertUnauthorized(changedSignature);
		assertUnauthorized(expiredToken(memberId, sessionId));
	}

	/**
	 * 액세스 토큰의 sid가 DB에 존재하지 않거나, 해당 세션이 만료되었거나, revoked_at이 설정되어 있으면 401로 거부된다.
	 */
	@Test
	@DisplayName("세션 없음·만료·폐기된 액세스 토큰은 거부된다")
	void missingExpiredAndRevokedSessionsAreRejected() throws Exception {
		UUID memberId = UUID.randomUUID();
		insertMember(memberId, "ACTIVE", Instant.now());

		assertUnauthorized(accessTokenService.issue(memberId, UUID.randomUUID()));

		UUID expiredSessionId = UUID.randomUUID();
		insertSession(expiredSessionId, memberId, Instant.now().minusSeconds(1), null);
		assertUnauthorized(accessTokenService.issue(memberId, expiredSessionId));

		UUID revokedSessionId = UUID.randomUUID();
		insertSession(revokedSessionId, memberId, Instant.now().plusSeconds(3600), Instant.now());
		assertUnauthorized(accessTokenService.issue(memberId, revokedSessionId));
	}

	/**
	 * 회원 상태가 WITHDRAWN이면 유효한 액세스 토큰으로 GET /members/me를 호출해도 401로 거부된다.
	 */
	@Test
	@DisplayName("탈퇴 회원의 액세스 토큰은 거부된다")
	void withdrawnMemberIsRejected() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO members (id, status, created_at, withdrawn_at, purge_after)
				VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days')
				""", memberId);
		insertSession(sessionId, memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);

		assertUnauthorized(accessTokenService.issue(memberId, sessionId));
	}

	/**
	 * 액세스 토큰으로 GET /members/me를 호출하면 응답의 memberId는 반드시 토큰에 포함된 회원의 ID와 일치하고 다른 회원의
	 * ID는 반환되지 않는다.
	 */
	@Test
	@DisplayName("토큰은 자신의 회원 정보만 조회할 수 있다")
	void tokenCanOnlyReadItsOwnMember() throws Exception {
		UUID authenticatedMemberId = UUID.randomUUID();
		UUID otherMemberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		insertMember(authenticatedMemberId, "ACTIVE", Instant.now());
		insertMember(otherMemberId, "ACTIVE", Instant.now());
		insertSession(sessionId, authenticatedMemberId, Instant.now().plus(30, ChronoUnit.DAYS), null);

		mockMvc.perform(get("/members/me").header("Authorization",
				"Bearer " + accessTokenService.issue(authenticatedMemberId, sessionId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.memberId").value(authenticatedMemberId.toString()))
				.andExpect(jsonPath("$.data.memberId").value(org.hamcrest.Matchers.not(otherMemberId.toString())));
	}

	private void insertMember(UUID memberId, String status, Instant createdAt) {
		jdbcTemplate.update("INSERT INTO members (id, status, created_at) VALUES (?, ?::member_status, ?)", memberId,
				status, Timestamp.from(createdAt));
	}

	private void insertSession(UUID sessionId, UUID memberId, Instant expiresAt, Instant revokedAt) {
		jdbcTemplate.update("""
				INSERT INTO auth_sessions
				    (id, member_id, refresh_token_hash, expires_at, revoked_at)
				VALUES (?, ?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(), Timestamp.from(expiresAt),
				revokedAt == null ? null : Timestamp.from(revokedAt));
	}

	private UUID insertTerm(String type, String version, Instant effectiveAt) {
		UUID termId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO terms (id, type, version, required, title, content, effective_at)
				VALUES (?, ?::term_type, ?, true, '약관', '내용', ?)
				""", termId, type, version, Timestamp.from(effectiveAt));
		return termId;
	}

	private void insertConsent(UUID memberId, UUID termId, boolean agreed) {
		jdbcTemplate.update("INSERT INTO term_consents (member_id, term_id, agreed) VALUES (?, ?, ?)", memberId, termId,
				agreed);
	}

	private String expiredToken(UUID memberId, UUID sessionId) {
		Instant issuedAt = Instant.now().minus(1, ChronoUnit.HOURS).minusSeconds(1);
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(memberId.toString()).claim("sid", sessionId.toString())
				.issuedAt(issuedAt).expiresAt(issuedAt.plus(1, ChronoUnit.HOURS)).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	private void assertUnauthorized(String token) throws Exception {
		var request = get("/members/me");
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		mockMvc.perform(request).andExpect(status().isUnauthorized()).andExpect(content().json("""
				{"success":false,"data":{"code":"UNAUTHORIZED","message":"인증이 필요합니다."}}
				"""));
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
		registry.add("security.jwt.secret-base64", () -> JWT_SECRET);
	}
}
