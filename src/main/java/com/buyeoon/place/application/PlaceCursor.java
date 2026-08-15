package com.buyeoon.place.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 거리·ID 순 정렬을 이어받는 키셋 커서다. 거리를 십진 문자열로 적으면 PostGIS가 계산한 값과 마지막 자리가 어긋나 같은 장소가
 * 다음 페이지에 다시 나오므로, 비트 패턴을 그대로 실어 왕복시킨다.
 */
public record PlaceCursor(double distanceMeters, UUID placeId) {

	public static PlaceCursor decode(String value) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			int separatorIndex = decoded.indexOf('_');
			double distanceMeters = Double
					.longBitsToDouble(Long.parseUnsignedLong(decoded.substring(0, separatorIndex), 16));
			UUID placeId = UUID.fromString(decoded.substring(separatorIndex + 1));
			if (!Double.isFinite(distanceMeters) || distanceMeters < 0) {
				throw new IllegalArgumentException("잘못된 커서입니다.");
			}
			return new PlaceCursor(distanceMeters, placeId);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("잘못된 커서입니다.", exception);
		}
	}

	public String encode() {
		String raw = Long.toHexString(Double.doubleToLongBits(distanceMeters)) + "_" + placeId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
}
