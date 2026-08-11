package com.buyeoon.member.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.buyeoon.member.entity.SocialProvider;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoSocialCredentialVerifierTests {

	private static final long EXPECTED_APP_ID = 1234L;
	private static final String TOKEN = "kakao-access-token";
	private static final String TOKEN_INFO_URL = "https://kapi.kakao.test/v1/user/access_token_info";

	private MockRestServiceServer server;
	private SocialCredentialVerifier verifier;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://kapi.kakao.test");
		server = MockRestServiceServer.bindTo(builder).build();
		verifier = new KakaoSocialCredentialVerifier(builder, EXPECTED_APP_ID);
	}

	/**
	 * 유효한 카카오 액세스 토큰을 검증하면 app_id 일치를 확인하고
	 * VerifiedSocialIdentity에 provider와 subject가 올바르게 담긴다.
	 */
	@Test
	@DisplayName("유효한 액세스 토큰은 검증된 카카오 subject를 반환한다")
	void validAccessTokenReturnsVerifiedKakaoSubject() {
		server.expect(once(), requestTo(TOKEN_INFO_URL)).andExpect(method(GET))
				.andExpect(header("Authorization", "Bearer " + TOKEN)).andRespond(withSuccess("""
						{"id":123456789,"expires_in":7199,"app_id":1234}
						""", MediaType.APPLICATION_JSON));

		VerifiedSocialIdentity identity = verifier.verify(new KakaoSocialCredential(TOKEN));

		assertEquals(SocialProvider.KAKAO, verifier.provider());
		assertEquals(SocialProvider.KAKAO, identity.provider());
		assertEquals("123456789", identity.subject());
		server.verify();
	}

	/**
	 * 카카오 API가 반환한 app_id가 설정된 EXPECTED_APP_ID와 다르면
	 * SocialAuthenticationFailedException이 발생한다.
	 */
	@Test
	@DisplayName("다른 카카오 앱의 토큰은 거부된다")
	void tokenFromAnotherKakaoAppIsRejected() {
		respondSuccess(123456789L, 7199L, 9999L);

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	/**
	 * 카카오 API 응답의 expires_in이 0이면
	 * SocialAuthenticationFailedException이 발생한다.
	 */
	@Test
	@DisplayName("만료된 토큰은 거부된다")
	void expiredTokenIsRejected() {
		respondSuccess(123456789L, 0L, EXPECTED_APP_ID);

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	/**
	 * 카카오 API가 401로 응답하면 SocialAuthenticationFailedException이 발생한다.
	 */
	@Test
	@DisplayName("카카오 인증 거부는 인증 실패로 분류된다")
	void kakaoAuthenticationRejectionIsClassifiedAsAuthenticationFailure() {
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
				.contentType(MediaType.APPLICATION_JSON).body("{\"msg\":\"invalid token\",\"code\":-401}"));

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	/**
	 * 카카오 API가 400으로 응답하면 SocialProviderUnavailableException이 발생한다.
	 */
	@Test
	@DisplayName("카카오 일시적 장애는 제공자 불가로 분류된다")
	void kakaoTemporaryFailureIsClassifiedAsProviderUnavailable() {
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON).body("{\"msg\":\"temporary failure\",\"code\":-1}"));

		assertThrows(SocialProviderUnavailableException.class, () -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	/**
	 * 카카오 API가 500으로 응답하면 SocialProviderUnavailableException이 발생한다.
	 */
	@Test
	@DisplayName("카카오 서버 오류는 제공자 불가로 분류된다")
	void kakaoServerErrorIsClassifiedAsProviderUnavailable() {
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThrows(SocialProviderUnavailableException.class, () -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	/**
	 * 네트워크 장애 시 SocialProviderUnavailableException이 발생하며,
	 * 예외 메시지에 액세스 토큰이 포함되지 않는다.
	 */
	@Test
	@DisplayName("네트워크 장애는 토큰을 노출하지 않고 제공자 불가로 분류된다")
	void networkFailureIsClassifiedAsProviderUnavailableWithoutExposingToken() {
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withException(new IOException("connection reset")));

		SocialProviderUnavailableException exception = assertThrows(SocialProviderUnavailableException.class,
				() -> verifier.verify(new KakaoSocialCredential(TOKEN)));

		assertFalse(exception.getMessage().contains(TOKEN));
		server.verify();
	}

	/**
	 * KakaoSocialCredential의 toString()에 액세스 토큰이 포함되지 않아야 한다.
	 */
	@Test
	@DisplayName("Credential의 toString은 액세스 토큰을 노출하지 않는다")
	void credentialStringDoesNotExposeAccessToken() {
		KakaoSocialCredential credential = new KakaoSocialCredential(TOKEN);

		assertFalse(credential.toString().contains(TOKEN));
	}

	private void respondSuccess(long id, long expiresIn, long appId) {
		String response = "{\"id\":%d,\"expires_in\":%d,\"app_id\":%d}".formatted(id, expiresIn, appId);
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
	}
}
