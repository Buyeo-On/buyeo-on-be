package com.buyeoon.badge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buyeoon.badge.application.BadgeMetricProviderRegistry;
import com.buyeoon.badge.entity.BadgeCategory;
import com.buyeoon.badge.entity.BadgeConditionEntity;
import com.buyeoon.badge.entity.BadgeEntity;
import com.buyeoon.badge.repository.BadgeConditionRepository;
import com.buyeoon.badge.repository.BadgeRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** UC-14 badge catalog startup 검증 로직의 단위 테스트다. */
class BadgeCatalogValidatorTests {

	private final BadgeRepository badgeRepository = mock(BadgeRepository.class);
	private final BadgeConditionRepository badgeConditionRepository = mock(BadgeConditionRepository.class);
	private final BadgeMetricProviderRegistry providerRegistry = mock(BadgeMetricProviderRegistry.class);
	private final BadgeCatalogValidator validator = new BadgeCatalogValidator(badgeRepository, badgeConditionRepository,
			providerRegistry);

	@Test
	@DisplayName("Empty catalog는 검증을 통과한다")
	void emptyCatalogPasses() {
		when(badgeRepository.findByRetiredAtIsNull()).thenReturn(List.of());

		validator.afterSingletonsInstantiated();

		verifyNoInteractions(badgeConditionRepository, providerRegistry);
	}

	@Test
	@DisplayName("조건과 지원 Provider를 갖춘 catalog는 검증을 통과한다")
	void validCatalogPasses() {
		BadgeEntity badge = badge("탐험가");
		when(badgeRepository.findByRetiredAtIsNull()).thenReturn(List.of(badge));
		when(badgeConditionRepository.findByIdBadgeIdIn(List.of(badge.getId())))
				.thenReturn(List.of(BadgeConditionEntity.create(badge.getId(), "MISSION_COMPLETED_COUNT", 1)));
		when(providerRegistry.get(BadgeMetric.MISSION_COMPLETED_COUNT)).thenReturn(null);

		validator.afterSingletonsInstantiated();
	}

	@Test
	@DisplayName("Condition이 없는 지급 가능 배지가 있으면 명확한 원인과 함께 예외를 던진다")
	void badgeWithoutConditionsFails() {
		BadgeEntity badge = badge("탐험가");
		when(badgeRepository.findByRetiredAtIsNull()).thenReturn(List.of(badge));
		when(badgeConditionRepository.findByIdBadgeIdIn(List.of(badge.getId()))).thenReturn(List.of());

		assertThatThrownBy(validator::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(badge.getId().toString()).hasMessageContaining("탐험가").hasMessageContaining("조건");
	}

	@Test
	@DisplayName("등록된 Provider가 없는 metric을 가진 지급 가능 배지가 있으면 명확한 원인과 함께 예외를 던진다")
	void badgeWithUnsupportedMetricFails() {
		BadgeEntity badge = badge("탐험가");
		when(badgeRepository.findByRetiredAtIsNull()).thenReturn(List.of(badge));
		when(badgeConditionRepository.findByIdBadgeIdIn(List.of(badge.getId())))
				.thenReturn(List.of(BadgeConditionEntity.create(badge.getId(), "UNSUPPORTED_METRIC", 1)));

		assertThatThrownBy(validator::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(badge.getId().toString()).hasMessageContaining("UNSUPPORTED_METRIC");
	}

	@Test
	@DisplayName("Registry에 Provider가 없는 지원 metric이면 명확한 원인과 함께 예외를 던진다")
	void badgeWithMetricMissingFromRegistryFails() {
		BadgeEntity badge = badge("탐험가");
		when(badgeRepository.findByRetiredAtIsNull()).thenReturn(List.of(badge));
		when(badgeConditionRepository.findByIdBadgeIdIn(List.of(badge.getId())))
				.thenReturn(List.of(BadgeConditionEntity.create(badge.getId(), "POINT_DONATION_COUNT", 1)));
		when(providerRegistry.get(BadgeMetric.POINT_DONATION_COUNT))
				.thenThrow(new IllegalArgumentException("지원하지 않는 배지 메트릭: POINT_DONATION_COUNT"));

		assertThatThrownBy(validator::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(badge.getId().toString()).hasMessageContaining("POINT_DONATION_COUNT");
	}

	private BadgeEntity badge(String name) {
		BadgeEntity badge = BadgeEntity.create(BadgeCategory.EXPLORATION, name, "설명", null, "조건");
		ReflectionTestUtils.setField(badge, "id", UUID.randomUUID());
		return badge;
	}
}
