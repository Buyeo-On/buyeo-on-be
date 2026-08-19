package com.buyeoon.badge.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buyeoon.badge.BadgeMetric;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 같은 {@link BadgeMetric}을 담당하는 {@link BadgeMetricProvider}가 중복 등록되면
 * {@link BadgeMetricProviderRegistry} 생성 시 애플리케이션 startup이 실패함을 좁은
 * ApplicationContext로 검증한다(ADR-003).
 */
class BadgeMetricProviderRegistryDuplicateProviderTests {

	@Test
	@DisplayName("같은 metric의 Provider가 두 개 등록되면 ApplicationContext startup이 실패한다")
	void duplicateProviderRegistrationFailsStartup() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.register(DuplicateProviderConfig.class, BadgeMetricProviderRegistry.class);

			assertThatThrownBy(context::refresh).hasStackTraceContaining("Duplicate key");
		}
	}

	@Test
	@DisplayName("서로 다른 metric의 Provider는 정상적으로 Registry에 등록된다")
	void distinctProvidersStartUpSuccessfully() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.register(DistinctProviderConfig.class, BadgeMetricProviderRegistry.class);
			context.refresh();

			BadgeMetricProviderRegistry registry = context.getBean(BadgeMetricProviderRegistry.class);
			assertThatThrownBy(() -> registry.get(BadgeMetric.POINT_DONATION_COUNT))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Configuration
	static class DuplicateProviderConfig {

		@Bean
		BadgeMetricProvider firstMissionProvider() {
			return stubProvider(BadgeMetric.MISSION_COMPLETED_COUNT);
		}

		@Bean
		BadgeMetricProvider secondMissionProvider() {
			return stubProvider(BadgeMetric.MISSION_COMPLETED_COUNT);
		}
	}

	@Configuration
	static class DistinctProviderConfig {

		@Bean
		BadgeMetricProvider missionProvider() {
			return stubProvider(BadgeMetric.MISSION_COMPLETED_COUNT);
		}

		@Bean
		BadgeMetricProvider heritageProvider() {
			return stubProvider(BadgeMetric.HERITAGE_VISITED_COUNT);
		}
	}

	private static BadgeMetricProvider stubProvider(BadgeMetric metric) {
		return new BadgeMetricProvider() {
			@Override
			public BadgeMetric metric() {
				return metric;
			}

			@Override
			public BadgeMetricSnapshot calculate(UUID memberId) {
				throw new UnsupportedOperationException();
			}
		};
	}
}
