package com.buyeoon.mission;

import java.time.Instant;
import java.util.UUID;

/**
 * badge 도메인이 {@code MISSION_COMPLETED_COUNT}를 판정할 때 사용하는 mission 도메인의
 * 스냅샷이다(ADR-003). {@code count}가 0이면 {@code latestTripId}와
 * {@code latestCompletedAt}은 {@code null}이다.
 */
public record MissionCompletionMetricSnapshot(long count, UUID latestTripId, Instant latestCompletedAt) {
}
