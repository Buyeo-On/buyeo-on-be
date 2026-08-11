package com.buyeoon.member.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenService {

	public static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);
	private static final int SECRET_BYTES = 32;

	private final SecureRandom secureRandom = new SecureRandom();

	public IssuedRefreshToken issue(UUID sessionId) {
		byte[] secretBytes = new byte[SECRET_BYTES];
		secureRandom.nextBytes(secretBytes);
		String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
		return new IssuedRefreshToken(sessionId + "." + secret, hash(secret),
				Instant.now().plus(REFRESH_TOKEN_LIFETIME));
	}

	public ParsedRefreshToken parse(String token) {
		if (token == null) {
			throw new InvalidRefreshTokenException();
		}
		int separator = token.indexOf('.');
		if (separator <= 0 || separator != token.lastIndexOf('.')) {
			throw new InvalidRefreshTokenException();
		}

		String sessionPart = token.substring(0, separator);
		String secret = token.substring(separator + 1);
		try {
			UUID sessionId = UUID.fromString(sessionPart);
			if (!sessionId.toString().equals(sessionPart) || secret.length() != 43
					|| Base64.getUrlDecoder().decode(secret).length != SECRET_BYTES) {
				throw new InvalidRefreshTokenException();
			}
			return new ParsedRefreshToken(sessionId, secret);
		} catch (IllegalArgumentException exception) {
			throw new InvalidRefreshTokenException();
		}
	}

	public boolean matches(String storedHash, ParsedRefreshToken token) {
		return storedHash != null && MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII),
				hash(token.secret()).getBytes(StandardCharsets.US_ASCII));
	}

	private String hash(String secret) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	public record IssuedRefreshToken(String token, String hash, Instant expiresAt) {

		@Override
		public String toString() {
			return "IssuedRefreshToken[token=REDACTED, hash=REDACTED, expiresAt=" + expiresAt + "]";
		}
	}

	public record ParsedRefreshToken(UUID sessionId, String secret) {

		@Override
		public String toString() {
			return "ParsedRefreshToken[sessionId=" + sessionId + ", secret=REDACTED]";
		}
	}
}
