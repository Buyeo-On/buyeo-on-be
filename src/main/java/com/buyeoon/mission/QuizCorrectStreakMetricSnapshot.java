package com.buyeoon.mission;

import java.time.Instant;
import java.util.UUID;

/**
 * badge 도메인이 {@code QUIZ_CORRECT_STREAK}를 판정할 때 사용하는 mission 도메인의
 * 스냅샷이다(ADR-003). {@code streak}이 0이면 {@code latestTripId}와
 * {@code latestAnsweredAt}은 {@code null}이다.
 */
public record QuizCorrectStreakMetricSnapshot(long streak, UUID latestTripId, Instant latestAnsweredAt) {
}
