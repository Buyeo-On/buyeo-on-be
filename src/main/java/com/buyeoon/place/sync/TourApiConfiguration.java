package com.buyeoon.place.sync;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class TourApiConfiguration {

	@Bean
	TourApiClient tourApiClient(@Value("${tourapi.base-url}") String baseUrl,
			@Value("${tourapi.service-key}") String serviceKey, @Value("${tourapi.area-code}") String areaCode,
			@Value("${tourapi.signgu-code}") String signguCode,
			@Value("${tourapi.connect-timeout}") Duration connectTimeout,
			@Value("${tourapi.read-timeout}") Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
		return new TourApiRestClient(restClientBuilder, baseUrl, serviceKey, areaCode, signguCode);
	}
}
