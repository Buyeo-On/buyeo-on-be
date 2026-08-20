package com.buyeoon.member.auth.social;

import java.net.http.HttpClient;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(prefix = "social.apple", name = "enabled", havingValue = "true")
public class AppleSocialAuthenticationConfiguration {

	@Bean
	SocialCredentialVerifier appleSocialCredentialVerifier(@Value("${social.apple.api-base-url}") String apiBaseUrl,
			@Value("${social.apple.client-id}") String clientId, @Value("${social.apple.team-id}") String teamId,
			@Value("${social.apple.key-id}") String keyId,
			@Value("${social.apple.private-key-base64}") String privateKeyBase64,
			@Value("${social.apple.client-secret-ttl}") Duration clientSecretTtl,
			@Value("${social.apple.connect-timeout}") Duration connectTimeout,
			@Value("${social.apple.read-timeout}") Duration readTimeout) {
		Clock clock = Clock.systemUTC();
		AppleClientSecretProvider clientSecretProvider = new AppleClientSecretGenerator(clientId, teamId, keyId,
				parsePrivateKey(privateKeyBase64), clientSecretTtl, clock);
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(apiBaseUrl).requestFactory(requestFactory);
		return new AppleSocialCredentialVerifier(restClientBuilder, clientId, clientSecretProvider, clock);
	}

	private ECPrivateKey parsePrivateKey(String privateKeyBase64) {
		try {
			byte[] encodedKey = Base64.getDecoder().decode(privateKeyBase64);
			PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
			if (!(privateKey instanceof ECPrivateKey ecPrivateKey)) {
				throw new IllegalArgumentException("Apple private key 형식이 올바르지 않습니다.");
			}
			return ecPrivateKey;
		} catch (Exception exception) {
			throw new IllegalArgumentException("Apple private key 형식이 올바르지 않습니다.", exception);
		}
	}
}
