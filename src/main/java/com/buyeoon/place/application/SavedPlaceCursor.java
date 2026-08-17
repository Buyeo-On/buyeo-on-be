package com.buyeoon.place.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** 저장 시각·ID 순 정렬을 이어받는 키셋 커서다. */
public record SavedPlaceCursor(Instant savedAt, UUID placeId) {

	public static SavedPlaceCursor decode(String value) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			int separatorIndex = decoded.indexOf('_');
			String[] parts = decoded.substring(0, separatorIndex).split("\\.");
			Instant savedAt = Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
			UUID placeId = UUID.fromString(decoded.substring(separatorIndex + 1));
			return new SavedPlaceCursor(savedAt, placeId);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("잘못된 커서입니다.", exception);
		}
	}

	public String encode() {
		String raw = savedAt.getEpochSecond() + "." + savedAt.getNano() + "_" + placeId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
}
