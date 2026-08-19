package com.buyeoon.point;

import java.time.Instant;
import java.util.UUID;

/**
 * badge 도메인이 {@code POINT_DONATION_COUNT}를 판정할 때 사용하는 point 도메인의
 * 스냅샷이다(ADR-003). {@code count}가 0이면 {@code latestTripId}와
 * {@code latestSettledAt}은 {@code null}이다.
 */
public record PointDonationMetricSnapshot(long count, UUID latestTripId, Instant latestSettledAt) {
}
