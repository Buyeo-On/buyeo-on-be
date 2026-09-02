package com.buyeoon.point.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** 누적 적립과 회원 ID로 랭킹 페이지 경계를 표현하는 불투명 커서다. */
public record PointRankingCursor(long cumulativeEarned, UUID memberId) {

	/** URL-safe Base64 커서를 검증하고 랭킹 경계 값으로 복원한다. */
	public static PointRankingCursor decode(String value) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			int separatorIndex = decoded.indexOf('_');
			long cumulativeEarned = Long.parseLong(decoded.substring(0, separatorIndex));
			UUID memberId = UUID.fromString(decoded.substring(separatorIndex + 1));
			if (cumulativeEarned < 1) {
				throw new IllegalArgumentException("누적 적립은 1 이상이어야 합니다.");
			}
			return new PointRankingCursor(cumulativeEarned, memberId);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("잘못된 랭킹 커서입니다.", exception);
		}
	}

	/** 랭킹 경계 값을 URL-safe Base64 문자열로 인코딩한다. */
	public String encode() {
		String raw = cumulativeEarned + "_" + memberId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
}
