package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.trip.HeritageVisitMetricQuery;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * trip 도메인의 공개 query seam으로 {@code HERITAGE_VISITED_COUNT}를 계산하는
 * Provider다(ADR-003).
 */
@Component
public class HeritageVisitedCountProvider implements BadgeMetricProvider {

	private final HeritageVisitMetricQuery heritageVisitMetricQuery;

	public HeritageVisitedCountProvider(HeritageVisitMetricQuery heritageVisitMetricQuery) {
		this.heritageVisitMetricQuery = heritageVisitMetricQuery;
	}

	@Override
	public BadgeMetric metric() {
		return BadgeMetric.HERITAGE_VISITED_COUNT;
	}

	@Override
	public long calculate(UUID memberId) {
		return heritageVisitMetricQuery.countUniquePlacesVisitedByMemberId(memberId);
	}
}
