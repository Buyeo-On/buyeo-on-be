package com.buyeoon.place.sync;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class KakaoImageSearchConfiguration {

	@Bean
	KakaoImageSearchClient kakaoImageSearchClient(@Value("${kakao.image-search.base-url}") String baseUrl,
			@Value("${kakao.image-search.rest-api-key:}") String restApiKey,
			@Value("${kakao.image-search.connect-timeout}") Duration connectTimeout,
			@Value("${kakao.image-search.read-timeout}") Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory);
		return new KakaoImageSearchRestClient(restClientBuilder, restApiKey);
	}
}
