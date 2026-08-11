package com.buyeoon.member.auth.social;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class KakaoSocialAuthenticationConfiguration {

	@Bean
	SocialCredentialVerifier kakaoSocialCredentialVerifier(@Value("${social.kakao.api-base-url}") String apiBaseUrl,
			@Value("${social.kakao.app-id}") long appId,
			@Value("${social.kakao.connect-timeout}") Duration connectTimeout,
			@Value("${social.kakao.read-timeout}") Duration readTimeout) {
		if (appId <= 0) {
			throw new IllegalArgumentException("카카오 앱 ID는 양수여야 합니다.");
		}
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(apiBaseUrl).requestFactory(requestFactory);
		return new KakaoSocialCredentialVerifier(restClientBuilder, appId);
	}
}
