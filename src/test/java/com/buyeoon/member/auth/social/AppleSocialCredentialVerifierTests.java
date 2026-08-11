package com.buyeoon.member.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.buyeoon.member.entity.SocialProvider;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AppleSocialCredentialVerifierTests {

	private static final String APPLE_ISSUER = "https://appleid.apple.com";
	private static final String CLIENT_ID = "com.buyeoon.app";
	private static final String KEY_ID = "APPLEKEY01";
	private static final String SUBJECT = "apple-user-subject";
	private static final String AUTHORIZATION_CODE = "single-use-authorization-code";
	private static final String NONCE = "login-request-nonce";
	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
	private static final String KEYS_URL = APPLE_ISSUER + "/auth/keys";
	private static final String TOKEN_URL = APPLE_ISSUER + "/auth/token";

	private MockRestServiceServer server;
	private SocialCredentialVerifier verifier;
	private RSAPublicKey applePublicKey;
	private RSAPrivateKey applePrivateKey;

	@BeforeEach
	void setUp() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keyPair = generator.generateKeyPair();
		applePublicKey = (RSAPublicKey) keyPair.getPublic();
		applePrivateKey = (RSAPrivateKey) keyPair.getPrivate();

		RestClient.Builder builder = RestClient.builder().baseUrl(APPLE_ISSUER);
		server = MockRestServiceServer.bindTo(builder).build();
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		verifier = new AppleSocialCredentialVerifier(builder, CLIENT_ID, () -> "generated-client-secret", clock);
	}

	/** 유효한 Apple 인증 정보가 검증된 Apple subject로 변환되는지 검증한다. */
	@Test
	@DisplayName("유효한 Apple 인증 정보는 검증된 subject를 반환한다")
	void validCredentialsReturnVerifiedAppleSubject() throws Exception {
		String identityToken = identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), NONCE,
				applePrivateKey);
		expectKeys();
		expectTokenExchange(AUTHORIZATION_CODE,
				identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), null, applePrivateKey));

		VerifiedSocialIdentity identity = verifier
				.verify(new AppleSocialCredential(AUTHORIZATION_CODE, identityToken, NONCE));

		assertEquals(SocialProvider.APPLE, verifier.provider());
		assertEquals(SocialProvider.APPLE, identity.provider());
		assertEquals(SUBJECT, identity.subject());
		server.verify();
	}

	/** Apple 공개 키와 다른 키로 서명한 identity token을 거부하는지 검증한다. */
	@Test
	@DisplayName("잘못된 서명의 identity token은 거부된다")
	void invalidSignatureIsRejected() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		RSAPrivateKey otherPrivateKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
		String identityToken = identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), NONCE,
				otherPrivateKey);
		expectKeys();

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new AppleSocialCredential(AUTHORIZATION_CODE, identityToken, NONCE)));
		server.verify();
	}

	/** Apple이 아닌 발급자의 identity token을 거부하는지 검증한다. */
	@Test
	@DisplayName("잘못된 발급자의 identity token은 거부된다")
	void invalidIssuerIsRejected() throws Exception {
		assertInvalidIdentityToken(identityToken(SUBJECT, "https://attacker.example", CLIENT_ID, NOW.plusSeconds(300),
				NONCE, applePrivateKey), NONCE);
	}

	/** 설정된 Apple client ID와 audience가 다른 token을 거부하는지 검증한다. */
	@Test
	@DisplayName("잘못된 audience의 identity token은 거부된다")
	void invalidAudienceIsRejected() throws Exception {
		assertInvalidIdentityToken(
				identityToken(SUBJECT, APPLE_ISSUER, "another-client", NOW.plusSeconds(300), NONCE, applePrivateKey),
				NONCE);
	}

	/** 만료 시각이 지난 identity token을 거부하는지 검증한다. */
	@Test
	@DisplayName("만료된 identity token은 거부된다")
	void expiredTokenIsRejected() throws Exception {
		assertInvalidIdentityToken(
				identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.minusSeconds(1), NONCE, applePrivateKey), NONCE);
	}

	/** 로그인 요청과 identity token의 nonce가 다르면 거부하는지 검증한다. */
	@Test
	@DisplayName("nonce가 다른 identity token은 거부된다")
	void mismatchedNonceIsRejected() throws Exception {
		assertInvalidIdentityToken(
				identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), "another-nonce", applePrivateKey),
				NONCE);
	}

	/** 인가 코드 교환 결과와 앱이 전달한 identity token의 subject 일치를 검증한다. */
	@Test
	@DisplayName("인가 코드와 identity token의 subject가 다르면 거부된다")
	void authorizationCodeSubjectMismatchIsRejected() throws Exception {
		String identityToken = identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), NONCE,
				applePrivateKey);
		expectKeys();
		expectTokenExchange(AUTHORIZATION_CODE,
				identityToken("another-subject", APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), null, applePrivateKey));

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new AppleSocialCredential(AUTHORIZATION_CODE, identityToken, NONCE)));
		server.verify();
	}

	/** Apple 토큰 API의 인증 거절을 사용자 인증 실패로 분류하는지 검증한다. */
	@Test
	@DisplayName("Apple 인증 거절은 인증 실패로 분류된다")
	void appleAuthenticationRejectionIsClassifiedAsAuthenticationFailure() throws Exception {
		String identityToken = identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), NONCE,
				applePrivateKey);
		expectKeys();
		server.expect(once(), requestTo(TOKEN_URL)).andExpect(method(POST))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":\"invalid_grant\"}"));

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new AppleSocialCredential(AUTHORIZATION_CODE, identityToken, NONCE)));
		server.verify();
	}

	/** Apple 공개 키 API의 서버 오류를 제공자 장애로 분류하는지 검증한다. */
	@Test
	@DisplayName("Apple 공개 키 API 서버 오류는 제공자 장애로 분류된다")
	void appleKeysServerErrorIsClassifiedAsProviderUnavailable() throws Exception {
		String identityToken = identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), NONCE,
				applePrivateKey);
		server.expect(once(), requestTo(KEYS_URL)).andExpect(method(GET))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThrows(SocialProviderUnavailableException.class,
				() -> verifier.verify(new AppleSocialCredential(AUTHORIZATION_CODE, identityToken, NONCE)));
		server.verify();
	}

	/** Apple 토큰 API의 서버 오류를 제공자 장애로 분류하는지 검증한다. */
	@Test
	@DisplayName("Apple 토큰 API 서버 오류는 제공자 장애로 분류된다")
	void appleTokenServerErrorIsClassifiedAsProviderUnavailable() throws Exception {
		String identityToken = identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), NONCE,
				applePrivateKey);
		expectKeys();
		server.expect(once(), requestTo(TOKEN_URL)).andExpect(method(POST))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThrows(SocialProviderUnavailableException.class,
				() -> verifier.verify(new AppleSocialCredential(AUTHORIZATION_CODE, identityToken, NONCE)));
		server.verify();
	}

	/** Apple 토큰 API의 네트워크 오류를 제공자 장애로 분류하는지 검증한다. */
	@Test
	@DisplayName("Apple 토큰 API 네트워크 오류는 제공자 장애로 분류된다")
	void appleTokenNetworkFailureIsClassifiedAsProviderUnavailable() throws Exception {
		String identityToken = identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), NONCE,
				applePrivateKey);
		expectKeys();
		server.expect(once(), requestTo(TOKEN_URL)).andExpect(method(POST))
				.andRespond(withException(new IOException("connection reset")));

		assertThrows(SocialProviderUnavailableException.class,
				() -> verifier.verify(new AppleSocialCredential(AUTHORIZATION_CODE, identityToken, NONCE)));
		server.verify();
	}

	/** 네트워크 오류 응답이 인증 정보 원문을 노출하지 않는지 검증한다. */
	@Test
	@DisplayName("네트워크 오류는 인증 정보를 노출하지 않는다")
	void networkFailureIsClassifiedAsProviderUnavailableWithoutExposingCredentials() throws Exception {
		String identityToken = identityToken(SUBJECT, APPLE_ISSUER, CLIENT_ID, NOW.plusSeconds(300), NONCE,
				applePrivateKey);
		server.expect(once(), requestTo(KEYS_URL)).andRespond(withException(new IOException("connection reset")));

		SocialProviderUnavailableException exception = assertThrows(SocialProviderUnavailableException.class,
				() -> verifier.verify(new AppleSocialCredential(AUTHORIZATION_CODE, identityToken, NONCE)));

		assertFalse(exception.getMessage().contains(AUTHORIZATION_CODE));
		assertFalse(exception.getMessage().contains(identityToken));
		assertFalse(exception.getMessage().contains(NONCE));
		server.verify();
	}

	/** 빈 Apple 인증 정보를 외부 호출 전에 거부하는지 검증한다. */
	@Test
	@DisplayName("빈 Apple 인증 정보는 외부 호출 없이 거부된다")
	void blankCredentialIsRejectedWithoutCallingApple() {
		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new AppleSocialCredential("", "", "")));
		server.verify();
	}

	/** Credential 문자열 표현에 인가 코드와 token, nonce가 포함되지 않는지 검증한다. */
	@Test
	@DisplayName("Credential의 문자열 표현은 인증 정보를 노출하지 않는다")
	void credentialStringDoesNotExposeSecrets() {
		AppleSocialCredential credential = new AppleSocialCredential(AUTHORIZATION_CODE, "identity-token", NONCE);

		assertFalse(credential.toString().contains(AUTHORIZATION_CODE));
		assertFalse(credential.toString().contains("identity-token"));
		assertFalse(credential.toString().contains(NONCE));
	}

	private void assertInvalidIdentityToken(String identityToken, String nonce) {
		expectKeys();

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new AppleSocialCredential(AUTHORIZATION_CODE, identityToken, nonce)));
		server.verify();
	}

	private void expectKeys() {
		RSAKey rsaKey = new RSAKey.Builder(applePublicKey).keyID(KEY_ID).algorithm(JWSAlgorithm.RS256).build();
		server.expect(once(), requestTo(KEYS_URL)).andExpect(method(GET))
				.andRespond(withSuccess(new JWKSet(rsaKey).toString(), MediaType.APPLICATION_JSON));
	}

	private void expectTokenExchange(String authorizationCode, String exchangedIdentityToken) {
		String response = "{\"id_token\":\"" + exchangedIdentityToken + "\"}";
		server.expect(once(), requestTo(TOKEN_URL)).andExpect(method(POST))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
				.andExpect(content().string(containsString("code=" + authorizationCode)))
				.andExpect(content().string(containsString("client_id=" + CLIENT_ID)))
				.andExpect(content().string(containsString("client_secret=generated-client-secret")))
				.andExpect(content().string(containsString("grant_type=authorization_code")))
				.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
	}

	private String identityToken(String subject, String issuer, String audience, Instant expiresAt, String nonce,
			RSAPrivateKey privateKey) throws JOSEException {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience)
				.issueTime(Date.from(NOW.minusSeconds(1))).expirationTime(Date.from(expiresAt));
		if (nonce != null) {
			claims.claim("nonce", nonce);
		}
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims.build());
		jwt.sign(new RSASSASigner(privateKey));
		return jwt.serialize();
	}
}
