package com.buyeoon.badge;

import com.buyeoon.badge.application.BadgeMetricProviderRegistry;
import com.buyeoon.badge.entity.BadgeConditionEntity;
import com.buyeoon.badge.entity.BadgeEntity;
import com.buyeoon.badge.repository.BadgeConditionRepository;
import com.buyeoon.badge.repository.BadgeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 지급 가능한(지급이 중단되지 않은) 배지 catalog가 조건과 지원 metric Provider를 갖췄는지 애플리케이션 startup에
 * 검증한다(ADR-003, 배지 rules #16). {@link SmartInitializingSingleton}은 모든 singleton
 * bean이 생성된 직후, embedded 서버가 요청을 받기 전에 실행되므로 검증 실패 시 ApplicationContext 자체가 기동에
 * 실패한다. Flyway로 관리하는 실제 schema가 없으면(H2 기반 경량 테스트 등) 검증할 catalog 자체가 없으므로
 * Flyway가 적용된 경우에만 동작한다.
 */
@Component
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BadgeCatalogValidator implements SmartInitializingSingleton {

	private final BadgeRepository badgeRepository;
	private final BadgeConditionRepository badgeConditionRepository;
	private final BadgeMetricProviderRegistry providerRegistry;

	public BadgeCatalogValidator(BadgeRepository badgeRepository, BadgeConditionRepository badgeConditionRepository,
			BadgeMetricProviderRegistry providerRegistry) {
		this.badgeRepository = badgeRepository;
		this.badgeConditionRepository = badgeConditionRepository;
		this.providerRegistry = providerRegistry;
	}

	@Override
	public void afterSingletonsInstantiated() {
		List<BadgeEntity> awardableBadges = badgeRepository.findByRetiredAtIsNull();
		if (awardableBadges.isEmpty()) {
			return;
		}

		Map<UUID, List<BadgeConditionEntity>> conditionsByBadgeId = badgeConditionRepository
				.findByIdBadgeIdIn(awardableBadges.stream().map(BadgeEntity::getId).toList()).stream()
				.collect(Collectors.groupingBy(condition -> condition.getId().badgeId()));

		List<String> errors = new ArrayList<>();
		for (BadgeEntity badge : awardableBadges) {
			validateBadge(badge, conditionsByBadgeId.getOrDefault(badge.getId(), List.of()), errors);
		}

		if (!errors.isEmpty()) {
			throw new IllegalStateException("배지 catalog 검증 실패:\n" + String.join("\n", errors));
		}
	}

	private void validateBadge(BadgeEntity badge, List<BadgeConditionEntity> conditions, List<String> errors) {
		if (conditions.isEmpty()) {
			errors.add("배지 '%s'(%s)에 조건이 하나도 없습니다.".formatted(badge.getName(), badge.getId()));
			return;
		}
		for (BadgeConditionEntity condition : conditions) {
			String metricKey = condition.getId().metricKey();
			try {
				providerRegistry.get(BadgeMetric.valueOf(metricKey));
			} catch (IllegalArgumentException e) {
				errors.add("배지 '%s'(%s)의 조건 metric '%s'에 등록된 Provider가 없습니다.".formatted(badge.getName(), badge.getId(),
						metricKey));
			}
		}
	}
}
