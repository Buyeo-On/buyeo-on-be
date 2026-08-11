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

	@Test
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

	@Test
	void tokenFromAnotherKakaoAppIsRejected() {
		respondSuccess(123456789L, 7199L, 9999L);

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	@Test
	void expiredTokenIsRejected() {
		respondSuccess(123456789L, 0L, EXPECTED_APP_ID);

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	@Test
	void kakaoAuthenticationRejectionIsClassifiedAsAuthenticationFailure() {
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
				.contentType(MediaType.APPLICATION_JSON).body("{\"msg\":\"invalid token\",\"code\":-401}"));

		assertThrows(SocialAuthenticationFailedException.class,
				() -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	@Test
	void kakaoTemporaryFailureIsClassifiedAsProviderUnavailable() {
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON).body("{\"msg\":\"temporary failure\",\"code\":-1}"));

		assertThrows(SocialProviderUnavailableException.class, () -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	@Test
	void kakaoServerErrorIsClassifiedAsProviderUnavailable() {
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThrows(SocialProviderUnavailableException.class, () -> verifier.verify(new KakaoSocialCredential(TOKEN)));
		server.verify();
	}

	@Test
	void networkFailureIsClassifiedAsProviderUnavailableWithoutExposingToken() {
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withException(new IOException("connection reset")));

		SocialProviderUnavailableException exception = assertThrows(SocialProviderUnavailableException.class,
				() -> verifier.verify(new KakaoSocialCredential(TOKEN)));

		assertFalse(exception.getMessage().contains(TOKEN));
		server.verify();
	}

	@Test
	void credentialStringDoesNotExposeAccessToken() {
		KakaoSocialCredential credential = new KakaoSocialCredential(TOKEN);

		assertFalse(credential.toString().contains(TOKEN));
	}

	private void respondSuccess(long id, long expiresIn, long appId) {
		String response = "{\"id\":%d,\"expires_in\":%d,\"app_id\":%d}".formatted(id, expiresIn, appId);
		server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
	}
}
