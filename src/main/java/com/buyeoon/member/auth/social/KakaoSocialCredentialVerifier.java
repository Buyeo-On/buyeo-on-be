package com.buyeoon.member.auth.social;

import com.buyeoon.member.entity.SocialProvider;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class KakaoSocialCredentialVerifier implements SocialCredentialVerifier {

	private static final String TOKEN_INFO_PATH = "/v1/user/access_token_info";
	private static final Pattern ERROR_CODE = Pattern.compile("\\\"code\\\"\\s*:\\s*(-?\\d+)");

	private final RestClient restClient;
	private final long expectedAppId;

	public KakaoSocialCredentialVerifier(RestClient.Builder restClientBuilder, long expectedAppId) {
		this.restClient = restClientBuilder.build();
		this.expectedAppId = expectedAppId;
	}

	@Override
	public SocialProvider provider() {
		return SocialProvider.KAKAO;
	}

	@Override
	public VerifiedSocialIdentity verify(SocialCredential credential) {
		if (!(credential instanceof KakaoSocialCredential kakaoCredential) || kakaoCredential.accessToken() == null
				|| kakaoCredential.accessToken().isBlank()) {
			throw new SocialAuthenticationFailedException();
		}

		KakaoAccessTokenInfo tokenInfo;
		try {
			tokenInfo = restClient.get().uri(TOKEN_INFO_PATH)
					.headers(headers -> headers.setBearerAuth(kakaoCredential.accessToken())).retrieve()
					.body(KakaoAccessTokenInfo.class);
		} catch (HttpClientErrorException exception) {
			if (exception.getStatusCode() == HttpStatus.BAD_REQUEST && kakaoErrorCode(exception) == -1) {
				throw new SocialProviderUnavailableException(exception);
			}
			throw new SocialAuthenticationFailedException();
		} catch (HttpServerErrorException exception) {
			throw new SocialProviderUnavailableException(exception);
		} catch (RestClientException exception) {
			throw new SocialProviderUnavailableException(exception);
		}

		if (tokenInfo == null || tokenInfo.id() == null || tokenInfo.id() <= 0 || tokenInfo.appId() == null) {
			throw new SocialProviderUnavailableException();
		}
		if (tokenInfo.expiresIn() == null || tokenInfo.expiresIn() <= 0 || tokenInfo.appId() != expectedAppId) {
			throw new SocialAuthenticationFailedException();
		}
		return new VerifiedSocialIdentity(SocialProvider.KAKAO, tokenInfo.id().toString());
	}

	private int kakaoErrorCode(HttpClientErrorException exception) {
		Matcher matcher = ERROR_CODE.matcher(exception.getResponseBodyAsString());
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
	}

	private record KakaoAccessTokenInfo(Long id, Long expires_in, Long app_id) {

		private Long expiresIn() {
			return expires_in;
		}

		private Long appId() {
			return app_id;
		}
	}
}
