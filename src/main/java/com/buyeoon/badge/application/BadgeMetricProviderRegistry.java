package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Spring이 주입한 모든 {@link BadgeMetricProvider}를 메트릭 키로 조회하는 Registry다(ADR-003).
 */
@Component
public class BadgeMetricProviderRegistry {

	private final Map<BadgeMetric, BadgeMetricProvider> providers;

	public BadgeMetricProviderRegistry(List<BadgeMetricProvider> providers) {
		this.providers = providers.stream()
				.collect(Collectors.toUnmodifiableMap(BadgeMetricProvider::metric, Function.identity()));
	}

	public BadgeMetricProvider get(BadgeMetric metric) {
		BadgeMetricProvider provider = providers.get(metric);
		if (provider == null) {
			throw new IllegalArgumentException("지원하지 않는 배지 메트릭: " + metric);
		}
		return provider;
	}
}
