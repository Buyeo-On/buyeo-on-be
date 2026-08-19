package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.mission.MissionCompletionMetricQuery;
import com.buyeoon.mission.MissionCompletionMetricSnapshot;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * mission 도메인의 공개 query seam으로 {@code MISSION_COMPLETED_COUNT}를 계산하는
 * Provider다(ADR-003).
 */
@Component
public class MissionCompletedCountProvider implements BadgeMetricProvider {

	private final MissionCompletionMetricQuery missionCompletionMetricQuery;

	public MissionCompletedCountProvider(MissionCompletionMetricQuery missionCompletionMetricQuery) {
		this.missionCompletionMetricQuery = missionCompletionMetricQuery;
	}

	@Override
	public BadgeMetric metric() {
		return BadgeMetric.MISSION_COMPLETED_COUNT;
	}

	@Override
	public BadgeMetricSnapshot calculate(UUID memberId) {
		MissionCompletionMetricSnapshot snapshot = missionCompletionMetricQuery.snapshot(memberId);
		return new BadgeMetricSnapshot(snapshot.count(), snapshot.latestTripId(), snapshot.latestCompletedAt());
	}
}
