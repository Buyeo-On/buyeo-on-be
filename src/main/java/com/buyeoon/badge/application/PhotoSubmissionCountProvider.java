package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.mission.MissionPhotoSubmissionMetricQuery;
import com.buyeoon.mission.MissionPhotoSubmissionMetricSnapshot;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * mission 도메인의 공개 query seam으로 {@code PHOTO_SUBMISSION_COUNT}를 계산하는
 * Provider다(ADR-003).
 */
@Component
public class PhotoSubmissionCountProvider implements BadgeMetricProvider {

	private final MissionPhotoSubmissionMetricQuery missionPhotoSubmissionMetricQuery;

	public PhotoSubmissionCountProvider(MissionPhotoSubmissionMetricQuery missionPhotoSubmissionMetricQuery) {
		this.missionPhotoSubmissionMetricQuery = missionPhotoSubmissionMetricQuery;
	}

	@Override
	public BadgeMetric metric() {
		return BadgeMetric.PHOTO_SUBMISSION_COUNT;
	}

	@Override
	public BadgeMetricSnapshot calculate(UUID memberId) {
		MissionPhotoSubmissionMetricSnapshot snapshot = missionPhotoSubmissionMetricQuery.snapshot(memberId);
		return new BadgeMetricSnapshot(snapshot.count(), snapshot.latestTripId(), snapshot.latestSubmittedAt());
	}
}
