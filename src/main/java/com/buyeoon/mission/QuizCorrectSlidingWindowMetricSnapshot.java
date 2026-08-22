package com.buyeoon.mission;

import java.time.Instant;
import java.util.UUID;

/**
 * badge 도메인이 {@code QUIZ_CORRECT_WITHIN_60_MINUTES_COUNT}를 판정할 때 사용하는 mission
 * 도메인의 스냅샷이다(ADR-003). {@code maxCorrectCount}는 회원의 전체 정답 퀴즈 제출 중 60분 이내에 들어오는
 * 최대 연속 구간의 정답 수다. {@code maxCorrectCount}가 0이면 {@code latestTripId}와
 * {@code latestSubmittedAt}은 {@code null}이다.
 */
public record QuizCorrectSlidingWindowMetricSnapshot(long maxCorrectCount, UUID latestTripId,
		Instant latestSubmittedAt) {
}
