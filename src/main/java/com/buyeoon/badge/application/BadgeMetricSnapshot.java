package com.buyeoon.badge.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 메트릭의 현재값과 그 값에 기여한 활동 중 가장 최근 활동의 여행 ID·시각을 담는 스냅샷이다(ADR-003). 값이 0이면
 * {@code latestContributingTripId}와 {@code latestContributionAt}은
 * {@code null}이다.
 */
public record BadgeMetricSnapshot(long value, UUID latestContributingTripId, Instant latestContributionAt) {
}
