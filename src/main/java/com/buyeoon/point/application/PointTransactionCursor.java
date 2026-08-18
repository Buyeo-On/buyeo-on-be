package com.buyeoon.point.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** 포인트 내역 목록 페이지네이션 커서(발생 시각, 내역 ID)를 인코딩·디코딩한다. */
public record PointTransactionCursor(Instant occurredAt, UUID transactionId) {

	public static PointTransactionCursor decode(String value) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			int separatorIndex = decoded.indexOf('_');
			long epochMicros = Long.parseLong(decoded.substring(0, separatorIndex));
			UUID transactionId = UUID.fromString(decoded.substring(separatorIndex + 1));
			Instant occurredAt = Instant.ofEpochSecond(Math.floorDiv(epochMicros, 1_000_000L),
					Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
			return new PointTransactionCursor(occurredAt, transactionId);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("잘못된 커서입니다.", exception);
		}
	}

	public String encode() {
		long epochMicros = Math.addExact(Math.multiplyExact(occurredAt.getEpochSecond(), 1_000_000L),
				occurredAt.getNano() / 1_000L);
		String raw = epochMicros + "_" + transactionId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
}
