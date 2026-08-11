package com.buyeoon.member.auth.social;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

final class AppleClientSecretGenerator implements AppleClientSecretProvider {

	private static final String APPLE_ISSUER = "https://appleid.apple.com";
	private static final Duration MAX_TTL = Duration.ofSeconds(15_777_000);

	private final String clientId;
	private final String teamId;
	private final String keyId;
	private final ECPrivateKey privateKey;
	private final Duration ttl;
	private final Clock clock;

	AppleClientSecretGenerator(String clientId, String teamId, String keyId, ECPrivateKey privateKey, Duration ttl,
			Clock clock) {
		if (isBlank(clientId) || isBlank(teamId) || isBlank(keyId) || privateKey == null || ttl == null || ttl.isZero()
				|| ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
			throw new IllegalArgumentException("Apple client secret 설정이 올바르지 않습니다.");
		}
		this.clientId = clientId;
		this.teamId = teamId;
		this.keyId = keyId;
		this.privateKey = privateKey;
		this.ttl = ttl;
		this.clock = clock;
	}

	@Override
	public String generate() {
		Instant now = clock.instant();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(teamId).subject(clientId).audience(APPLE_ISSUER)
				.issueTime(Date.from(now)).expirationTime(Date.from(now.plus(ttl))).build();
		SignedJWT clientSecret = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
		try {
			clientSecret.sign(new ECDSASigner(privateKey));
			return clientSecret.serialize();
		} catch (JOSEException exception) {
			throw new IllegalStateException("Apple client secret 생성에 실패했습니다.", exception);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
