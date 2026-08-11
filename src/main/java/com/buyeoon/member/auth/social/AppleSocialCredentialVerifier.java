package com.buyeoon.member.auth.social;

import com.buyeoon.member.entity.SocialProvider;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Clock;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class AppleSocialCredentialVerifier implements SocialCredentialVerifier {

	private static final String APPLE_ISSUER = "https://appleid.apple.com";
	private static final String KEYS_PATH = "/auth/keys";
	private static final String TOKEN_PATH = "/auth/token";

	private final RestClient restClient;
	private final String clientId;
	private final AppleClientSecretProvider clientSecretProvider;
	private final Clock clock;

	AppleSocialCredentialVerifier(RestClient.Builder restClientBuilder, String clientId,
			AppleClientSecretProvider clientSecretProvider, Clock clock) {
		this.restClient = restClientBuilder.build();
		this.clientId = clientId;
		this.clientSecretProvider = clientSecretProvider;
		this.clock = clock;
	}

	@Override
	public SocialProvider provider() {
		return SocialProvider.APPLE;
	}

	@Override
	public VerifiedSocialIdentity verify(SocialCredential credential) {
		if (!(credential instanceof AppleSocialCredential appleCredential)
				|| isBlank(appleCredential.authorizationCode()) || isBlank(appleCredential.identityToken())
				|| isBlank(appleCredential.nonce())) {
			throw new SocialAuthenticationFailedException();
		}

		JWKSet appleKeys = fetchAppleKeys();
		String identitySubject = verifyIdentityToken(appleCredential.identityToken(), appleCredential.nonce(),
				appleKeys);
		AppleTokenResponse tokenResponse = exchangeAuthorizationCode(appleCredential.authorizationCode());
		if (tokenResponse == null || isBlank(tokenResponse.idToken())) {
			throw new SocialProviderUnavailableException();
		}
		String exchangedSubject = verifyIdentityToken(tokenResponse.idToken(), null, appleKeys);
		if (!constantTimeEquals(identitySubject, exchangedSubject)) {
			throw new SocialAuthenticationFailedException();
		}
		return new VerifiedSocialIdentity(SocialProvider.APPLE, identitySubject);
	}

	private JWKSet fetchAppleKeys() {
		String response;
		try {
			response = restClient.get().uri(KEYS_PATH).retrieve().body(String.class);
		} catch (RestClientException exception) {
			throw new SocialProviderUnavailableException(exception);
		}
		if (isBlank(response)) {
			throw new SocialProviderUnavailableException();
		}
		try {
			return JWKSet.parse(response);
		} catch (ParseException exception) {
			throw new SocialProviderUnavailableException(exception);
		}
	}

	private AppleTokenResponse exchangeAuthorizationCode(String authorizationCode) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", clientId);
		form.add("client_secret", clientSecretProvider.generate());
		form.add("code", authorizationCode);
		form.add("grant_type", "authorization_code");
		try {
			return restClient.post().uri(TOKEN_PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
					.retrieve().body(AppleTokenResponse.class);
		} catch (HttpClientErrorException exception) {
			throw new SocialAuthenticationFailedException();
		} catch (HttpServerErrorException exception) {
			throw new SocialProviderUnavailableException(exception);
		} catch (RestClientException exception) {
			throw new SocialProviderUnavailableException(exception);
		}
	}

	private String verifyIdentityToken(String serializedToken, String expectedNonce, JWKSet appleKeys) {
		try {
			SignedJWT token = SignedJWT.parse(serializedToken);
			if (!JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm())) {
				throw new SocialAuthenticationFailedException();
			}
			JWK key = appleKeys.getKeyByKeyId(token.getHeader().getKeyID());
			if (!(key instanceof RSAKey rsaKey) || !token.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
				throw new SocialAuthenticationFailedException();
			}

			JWTClaimsSet claims = token.getJWTClaimsSet();
			String subject = claims.getSubject();
			List<String> audience = claims.getAudience();
			if (isBlank(subject) || !APPLE_ISSUER.equals(claims.getIssuer()) || audience == null
					|| !audience.contains(clientId) || claims.getExpirationTime() == null
					|| !claims.getExpirationTime().toInstant().isAfter(clock.instant())) {
				throw new SocialAuthenticationFailedException();
			}
			if (expectedNonce != null && !constantTimeEquals(expectedNonce, claims.getStringClaim("nonce"))) {
				throw new SocialAuthenticationFailedException();
			}
			return subject;
		} catch (ParseException | JOSEException exception) {
			throw new SocialAuthenticationFailedException();
		}
	}

	private boolean constantTimeEquals(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				actual.getBytes(StandardCharsets.UTF_8));
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private record AppleTokenResponse(String id_token) {

		private String idToken() {
			return id_token;
		}
	}
}
