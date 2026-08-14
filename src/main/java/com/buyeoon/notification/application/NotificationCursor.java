package com.buyeoon.notification.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record NotificationCursor(Instant occurredAt, UUID notificationId) {

	public static NotificationCursor decode(String value) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			int separatorIndex = decoded.indexOf('_');
			long epochMicros = Long.parseLong(decoded.substring(0, separatorIndex));
			UUID notificationId = UUID.fromString(decoded.substring(separatorIndex + 1));
			Instant occurredAt = Instant.ofEpochSecond(Math.floorDiv(epochMicros, 1_000_000L),
					Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
			return new NotificationCursor(occurredAt, notificationId);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("잘못된 커서입니다.", exception);
		}
	}

	public String encode() {
		long epochMicros = Math.addExact(Math.multiplyExact(occurredAt.getEpochSecond(), 1_000_000L),
				occurredAt.getNano() / 1_000L);
		String raw = epochMicros + "_" + notificationId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
}
