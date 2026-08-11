package com.buyeoon.member.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppleClientSecretGeneratorTests {

	/** Apple client secret이 필수 claim과 ES256 서명을 포함하는지 검증한다. */
	@Test
	@DisplayName("Apple client secret은 필수 claim과 ES256 서명을 포함한다")
	void createsAppleClientSecretWithRequiredClaimsAndSignature() throws Exception {
		KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
		keyPairGenerator.initialize(256);
		KeyPair keyPair = keyPairGenerator.generateKeyPair();
		Instant now = Instant.parse("2026-08-12T00:00:00Z");
		AppleClientSecretGenerator generator = new AppleClientSecretGenerator("com.buyeoon.app", "TEAMID1234",
				"KEYID12345", (ECPrivateKey) keyPair.getPrivate(), Duration.ofMinutes(5),
				Clock.fixed(now, ZoneOffset.UTC));

		SignedJWT clientSecret = SignedJWT.parse(generator.generate());

		assertEquals(JWSAlgorithm.ES256, clientSecret.getHeader().getAlgorithm());
		assertEquals("KEYID12345", clientSecret.getHeader().getKeyID());
		assertEquals("TEAMID1234", clientSecret.getJWTClaimsSet().getIssuer());
		assertEquals("com.buyeoon.app", clientSecret.getJWTClaimsSet().getSubject());
		assertEquals(List.of("https://appleid.apple.com"), clientSecret.getJWTClaimsSet().getAudience());
		assertEquals(now, clientSecret.getJWTClaimsSet().getIssueTime().toInstant());
		assertEquals(now.plus(Duration.ofMinutes(5)), clientSecret.getJWTClaimsSet().getExpirationTime().toInstant());
		assertTrue(clientSecret.verify(new ECDSAVerifier((ECPublicKey) keyPair.getPublic())));
	}
}
