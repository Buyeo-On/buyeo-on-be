package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.point.PointDonationMetricQuery;
import com.buyeoon.point.PointDonationMetricSnapshot;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * point 도메인의 공개 query seam으로 {@code POINT_DONATION_COUNT}를 계산하는
 * Provider다(ADR-003).
 */
@Component
public class PointDonationCountProvider implements BadgeMetricProvider {

	private final PointDonationMetricQuery pointDonationMetricQuery;

	public PointDonationCountProvider(PointDonationMetricQuery pointDonationMetricQuery) {
		this.pointDonationMetricQuery = pointDonationMetricQuery;
	}

	@Override
	public BadgeMetric metric() {
		return BadgeMetric.POINT_DONATION_COUNT;
	}

	@Override
	public BadgeMetricSnapshot calculate(UUID memberId) {
		PointDonationMetricSnapshot snapshot = pointDonationMetricQuery.snapshot(memberId);
		return new BadgeMetricSnapshot(snapshot.count(), snapshot.latestTripId(), snapshot.latestSettledAt());
	}
}
