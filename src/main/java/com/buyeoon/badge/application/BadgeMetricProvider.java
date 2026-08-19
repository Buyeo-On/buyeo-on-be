package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import java.util.UUID;

/** 메트릭별 원본 활동 데이터를 집계하는 Provider다(ADR-003). */
public interface BadgeMetricProvider {

	BadgeMetric metric();

	/**
	 * 회원의 전체 여행 활동을 기준으로 현재 메트릭값과 가장 최근 기여 활동의 여행·시각을 계산한다.
	 */
	BadgeMetricSnapshot calculate(UUID memberId);
}
