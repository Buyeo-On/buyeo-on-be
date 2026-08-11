package com.buyeoon.member.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class AccessTokenService {

	public static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofHours(1);

	private final JwtEncoder jwtEncoder;

	public AccessTokenService(JwtEncoder jwtEncoder) {
		this.jwtEncoder = jwtEncoder;
	}

	public String issue(UUID memberId, UUID sessionId) {
		Instant issuedAt = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(memberId.toString()).claim("sid", sessionId.toString())
				.issuedAt(issuedAt).expiresAt(issuedAt.plus(ACCESS_TOKEN_LIFETIME)).build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
