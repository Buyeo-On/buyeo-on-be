package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.social.AppleSocialCredential;
import com.buyeoon.member.auth.social.KakaoSocialCredential;
import com.buyeoon.member.auth.social.SocialAuthenticationFailedException;
import com.buyeoon.member.auth.social.SocialCredentialVerifier;
import com.buyeoon.member.auth.social.SocialProviderUnavailableException;
import com.buyeoon.member.auth.social.VerifiedSocialIdentity;
import com.buyeoon.member.entity.SocialProvider;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SocialLoginIntegrationTests {

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
	private JwtDecoder jwtDecoder;

	@MockitoBean(name = "kakaoSocialCredentialVerifier")
	private SocialCredentialVerifier kakaoVerifier;

	@MockitoBean(name = "appleSocialCredentialVerifier")
	private SocialCredentialVerifier appleVerifier;

	@BeforeEach
	void setUpVerifiers() {
		given(kakaoVerifier.provider()).willReturn(SocialProvider.KAKAO);
		given(kakaoVerifier.verify(any(KakaoSocialCredential.class))).willAnswer(invocation -> {
			KakaoSocialCredential credential = invocation.getArgument(0);
			return new VerifiedSocialIdentity(SocialProvider.KAKAO, credential.accessToken());
		});
		given(appleVerifier.provider()).willReturn(SocialProvider.APPLE);
		given(appleVerifier.verify(any(AppleSocialCredential.class))).willAnswer(invocation -> {
			AppleSocialCredential credential = invocation.getArgument(0);
			return new VerifiedSocialIdentity(SocialProvider.APPLE, credential.authorizationCode());
		});
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM social_accounts");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 검증된 카카오 계정에 연결된 활성 회원이 새 인증 세션으로 로그인하는지 검증한다. */
	@Test
	@DisplayName("기존 활성 회원은 카카오 계정으로 로그인한다")
	void existingActiveMemberLogsInWithKakao() throws Exception {
		UUID memberId = insertActiveMemberWithSocialAccount(SocialProvider.KAKAO, "kakao-existing");

		MvcResult result = kakaoLogin("kakao-existing").andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.isNewMember").value(false))
				.andExpect(jsonPath("$.data.member.memberId").value(memberId.toString()))
				.andExpect(jsonPath("$.data.member.status").value("ACTIVE")).andReturn();

		assertIssuedTokens(result, memberId);
		assertThat(count("auth_sessions")).isEqualTo(1);
	}

	/** 검증된 Apple 계정에 연결된 활성 회원이 새 인증 세션으로 로그인하는지 검증한다. */
	@Test
	@DisplayName("기존 활성 회원은 Apple 계정으로 로그인한다")
	void existingActiveMemberLogsInWithApple() throws Exception {
		UUID memberId = insertActiveMemberWithSocialAccount(SocialProvider.APPLE, "apple-existing");

		MvcResult result = appleLogin("apple-existing").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.isNewMember").value(false))
				.andExpect(jsonPath("$.data.member.memberId").value(memberId.toString())).andReturn();

		assertIssuedTokens(result, memberId);
		assertThat(count("auth_sessions")).isEqualTo(1);
	}

	/** 가입되지 않은 subject가 회원·소셜 계정·기본 설정·세션을 원자적으로 생성하는지 검증한다. */
	@Test
	@DisplayName("신규 소셜 계정은 기본 온보딩 상태로 가입한다")
	void newSocialAccountCreatesMemberSettingsAndSession() throws Exception {
		MvcResult result = kakaoLogin("kakao-new").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.isNewMember").value(true))
				.andExpect(jsonPath("$.data.member.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.member.displayName").value((Object) null))
				.andExpect(jsonPath("$.data.member.characterId").value((Object) null))
				.andExpect(jsonPath("$.data.member.requiredTermsAgreed").value(false))
				.andExpect(jsonPath("$.data.member.citizenCardIssued").value(false)).andReturn();

		UUID memberId = UUID.fromString(JsonPath.read(response(result), "$.data.member.memberId"));
		assertIssuedTokens(result, memberId);
		assertThat(count("members")).isEqualTo(1);
		assertThat(count("social_accounts")).isEqualTo(1);
		assertThat(count("member_settings")).isEqualTo(1);
		assertThat(count("auth_sessions")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForMap("""
				SELECT nearby_quiz_notification_enabled, dark_mode_enabled
				FROM member_settings
				WHERE member_id = ?
				""", memberId)).containsEntry("nearby_quiz_notification_enabled", false)
				.containsEntry("dark_mode_enabled", false);
	}

	/** 같은 소셜 계정의 반복 로그인은 회원 정보를 중복 생성하지 않고 세션만 추가하는지 검증한다. */
	@Test
	@DisplayName("반복 로그인은 회원을 중복 생성하지 않고 새 세션을 만든다")
	void repeatedLoginOnlyCreatesAnotherSession() throws Exception {
		kakaoLogin("kakao-repeat").andExpect(status().isOk()).andExpect(jsonPath("$.data.isNewMember").value(true));

		kakaoLogin("kakao-repeat").andExpect(status().isOk()).andExpect(jsonPath("$.data.isNewMember").value(false));

		assertThat(count("members")).isEqualTo(1);
		assertThat(count("social_accounts")).isEqualTo(1);
		assertThat(count("member_settings")).isEqualTo(1);
		assertThat(count("auth_sessions")).isEqualTo(2);
	}

	/** 같은 신규 subject의 동시 로그인도 회원은 하나, 성공한 세션은 요청마다 생성하는지 검증한다. */
	@Test
	@DisplayName("동시 신규 로그인은 회원 하나와 세션 두 개를 만든다")
	void concurrentNewLoginCreatesOneMemberAndOneSessionPerRequest() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try {
			var request = (java.util.concurrent.Callable<MvcResult>) () -> {
				ready.countDown();
				start.await();
				return kakaoLogin("kakao-concurrent").andReturn();
			};
			Future<MvcResult> first = executor.submit(request);
			Future<MvcResult> second = executor.submit(request);
			ready.await();
			start.countDown();

			List<MvcResult> results = List.of(first.get(), second.get());
			assertThat(results).extracting(result -> result.getResponse().getStatus()).containsOnly(200);
			assertThat(results).extracting(result -> JsonPath.<Boolean>read(response(result), "$.data.isNewMember"))
					.containsExactlyInAnyOrder(true, false);
		} finally {
			executor.shutdownNow();
		}

		assertThat(count("members")).isEqualTo(1);
		assertThat(count("social_accounts")).isEqualTo(1);
		assertThat(count("member_settings")).isEqualTo(1);
		assertThat(count("auth_sessions")).isEqualTo(2);
	}

	/** 기본 설정 저장이 실패하면 신규 회원과 소셜 계정도 함께 롤백되는지 검증한다. */
	@Test
	@DisplayName("기본 설정 저장 실패는 신규 가입 전체를 롤백한다")
	void settingsSaveFailureRollsBackRegistration() throws Exception {
		executeAsAdmin("REVOKE INSERT ON member_settings FROM buyeoon_app");
		try {
			assertThatThrownBy(() -> kakaoLogin("kakao-settings-failure").andReturn())
					.isInstanceOf(ServletException.class);
			assertRegistrationTablesAreEmpty();
		} finally {
			executeAsAdmin("GRANT INSERT ON member_settings TO buyeoon_app");
		}
	}

	/** 인증 세션 저장이 실패하면 신규 회원·소셜 계정·기본 설정까지 롤백되는지 검증한다. */
	@Test
	@DisplayName("인증 세션 저장 실패는 신규 가입 전체를 롤백한다")
	void sessionSaveFailureRollsBackRegistration() throws Exception {
		executeAsAdmin("REVOKE INSERT ON auth_sessions FROM buyeoon_app");
		try {
			assertThatThrownBy(() -> kakaoLogin("kakao-session-failure").andReturn())
					.isInstanceOf(ServletException.class);
			assertRegistrationTablesAreEmpty();
		} finally {
			executeAsAdmin("GRANT INSERT ON auth_sessions TO buyeoon_app");
		}
	}

	/** provider별 필수 필드가 없거나 허용되지 않은 필드가 있으면 400 응답인지 검증한다. */
	@Test
	@DisplayName("형식이 잘못된 소셜 로그인 요청은 400으로 거부된다")
	void malformedSocialLoginRequestIsRejected() throws Exception {
		assertInvalidRequest("{}");
		assertInvalidRequest("{\"provider\":\"KAKAO\",\"accessToken\":\"\"}");
		assertInvalidRequest("{\"provider\":\"APPLE\",\"authorizationCode\":\"code\",\"identityToken\":\"token\"}");
		assertInvalidRequest("{\"provider\":\"KAKAO\",\"accessToken\":\"token\",\"nonce\":\"extra\"}");
		assertInvalidRequest("{\"provider\":\"UNKNOWN\",\"accessToken\":\"token\"}");
		verifyNoInteractions(kakaoVerifier, appleVerifier);
	}

	/** 제공자 어댑터가 인증 정보를 거절하면 회원과 세션 없이 401을 반환하는지 검증한다. */
	@Test
	@DisplayName("소셜 인증 실패는 401을 반환하고 DB를 변경하지 않는다")
	void socialAuthenticationFailureDoesNotChangeDatabase() throws Exception {
		given(kakaoVerifier.verify(any(KakaoSocialCredential.class)))
				.willThrow(new SocialAuthenticationFailedException());

		kakaoLogin("invalid-token").andExpect(status().isUnauthorized()).andExpect(content().json("""
				{"success":false,"data":{"code":"SOCIAL_AUTHENTICATION_FAILED","message":"소셜 인증에 실패했습니다."}}
				"""));

		assertRegistrationTablesAreEmpty();
	}

	/** 탈퇴 회원에게 연결된 subject는 재가입시키지 않고 409를 반환하는지 검증한다. */
	@Test
	@DisplayName("탈퇴 회원의 소셜 계정은 재가입 없이 409를 반환한다")
	void withdrawnMemberCannotLogInOrRegisterAgain() throws Exception {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO members (id, status, withdrawn_at, purge_after)
				VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days')
				""", memberId);
		insertSocialAccount(memberId, SocialProvider.KAKAO, "kakao-withdrawn");

		kakaoLogin("kakao-withdrawn").andExpect(status().isConflict()).andExpect(content().json("""
				{"success":false,"data":{"code":"MEMBER_WITHDRAWN","message":"탈퇴한 회원은 로그인할 수 없습니다."}}
				"""));

		assertThat(count("members")).isEqualTo(1);
		assertThat(count("social_accounts")).isEqualTo(1);
		assertThat(count("auth_sessions")).isZero();
	}

	/** 제공자 장애가 발생하면 회원이나 세션을 생성하지 않고 502를 반환하는지 검증한다. */
	@Test
	@DisplayName("소셜 제공자 장애는 502를 반환하고 DB를 변경하지 않는다")
	void socialProviderFailureDoesNotChangeDatabase() throws Exception {
		given(kakaoVerifier.verify(any(KakaoSocialCredential.class)))
				.willThrow(new SocialProviderUnavailableException());

		kakaoLogin("provider-down").andExpect(status().isBadGateway()).andExpect(content().json("""
				{"success":false,"data":{"code":"SOCIAL_PROVIDER_UNAVAILABLE","message":"소셜 로그인을 일시적으로 사용할 수 없습니다."}}
				"""));

		assertRegistrationTablesAreEmpty();
	}

	private ResultActions kakaoLogin(String accessToken) throws Exception {
		return socialLogin("""
				{"provider":"KAKAO","accessToken":"%s"}
				""".formatted(accessToken));
	}

	private ResultActions appleLogin(String authorizationCode) throws Exception {
		return socialLogin("""
				{
				  "provider":"APPLE",
				  "authorizationCode":"%s",
				  "identityToken":"apple-identity-token",
				  "nonce":"apple-nonce"
				}
				""".formatted(authorizationCode));
	}

	private ResultActions socialLogin(String requestBody) throws Exception {
		return mockMvc.perform(post("/auth/social-login").contentType(MediaType.APPLICATION_JSON).content(requestBody));
	}

	private void assertInvalidRequest(String requestBody) throws Exception {
		socialLogin(requestBody).andExpect(status().isBadRequest()).andExpect(content().json("""
				{"success":false,"data":{"code":"INVALID_REQUEST","message":"요청 값이 올바르지 않습니다."}}
				"""));
	}

	private UUID insertActiveMemberWithSocialAccount(SocialProvider provider, String subject) {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		insertSocialAccount(memberId, provider, subject);
		jdbcTemplate.update("INSERT INTO member_settings (member_id) VALUES (?)", memberId);
		return memberId;
	}

	private void insertSocialAccount(UUID memberId, SocialProvider provider, String subject) {
		jdbcTemplate.update("""
				INSERT INTO social_accounts (member_id, provider, provider_subject)
				VALUES (?, ?::social_provider, ?)
				""", memberId, provider.name(), subject);
	}

	private void assertIssuedTokens(MvcResult result, UUID memberId) throws Exception {
		String response = response(result);
		String accessToken = JsonPath.read(response, "$.data.accessToken");
		String refreshToken = JsonPath.read(response, "$.data.refreshToken");
		Number expiresInSeconds = JsonPath.read(response, "$.data.expiresInSeconds");
		String[] refreshParts = refreshToken.split("\\.");
		UUID sessionId = UUID.fromString(refreshParts[0]);

		assertThat(expiresInSeconds.longValue()).isEqualTo(3600L);
		assertThat(refreshParts).hasSize(2);
		assertThat(jdbcTemplate.queryForObject("SELECT refresh_token_hash FROM auth_sessions WHERE id = ?",
				String.class, sessionId)).isEqualTo(hash(refreshParts[1])).doesNotContain(refreshToken)
				.doesNotContain(refreshParts[1]);

		Jwt jwt = jwtDecoder.decode(accessToken);
		assertThat(jwt.getSubject()).isEqualTo(memberId.toString());
		assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
	}

	private void assertRegistrationTablesAreEmpty() {
		assertThat(count("members")).isZero();
		assertThat(count("social_accounts")).isZero();
		assertThat(count("member_settings")).isZero();
		assertThat(count("auth_sessions")).isZero();
	}

	private long count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	private String hash(String secret) throws Exception {
		return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
	}

	private String response(MvcResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	private void executeAsAdmin(String sql) throws SQLException {
		try (var connection = DriverManager.getConnection(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(),
				POSTGIS.getPassword()); var statement = connection.createStatement()) {
			statement.execute(sql);
		}
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
