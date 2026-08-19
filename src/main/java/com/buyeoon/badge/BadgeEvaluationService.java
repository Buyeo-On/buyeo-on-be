package com.buyeoon.badge;

import com.buyeoon.badge.application.BadgeMetricProviderRegistry;
import com.buyeoon.badge.entity.BadgeConditionEntity;
import com.buyeoon.badge.entity.BadgeEntity;
import com.buyeoon.badge.repository.BadgeConditionRepository;
import com.buyeoon.badge.repository.BadgeRepository;
import com.buyeoon.badge.repository.MemberBadgeRepository;
import com.buyeoon.notification.NotificationCreationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 도메인이 activity 처리 후 미획득 배지를 판정할 때 사용하는 badge 도메인의 공개 seam이다(ADR-003). 호출하는
 * application service가 source 변경을 flush한 뒤 같은 transaction에서 동기 호출해야 방금 확정한
 * activity를 포함해 판정한다.
 */
@Service
public class BadgeEvaluationService {

	private final BadgeMetricProviderRegistry providerRegistry;
	private final BadgeRepository badgeRepository;
	private final BadgeConditionRepository badgeConditionRepository;
	private final MemberBadgeRepository memberBadgeRepository;
	private final NotificationCreationService notificationCreationService;

	public BadgeEvaluationService(BadgeMetricProviderRegistry providerRegistry, BadgeRepository badgeRepository,
			BadgeConditionRepository badgeConditionRepository, MemberBadgeRepository memberBadgeRepository,
			NotificationCreationService notificationCreationService) {
		this.providerRegistry = providerRegistry;
		this.badgeRepository = badgeRepository;
		this.badgeConditionRepository = badgeConditionRepository;
		this.memberBadgeRepository = memberBadgeRepository;
		this.notificationCreationService = notificationCreationService;
	}

	/**
	 * 변경된 metric과 관련된 미획득 배지의 모든 조건을 판정하고, 처음 충족한 배지를 badge ID 오름차순으로 지급한다.
	 * Activity, 새 배지 획득과 {@code BADGE} 알림은 호출자의 transaction에 참여해 같은 transaction으로
	 * 확정한다.
	 */
	@Transactional
	public List<AwardedBadgeResult> award(UUID memberId, UUID tripId, Set<BadgeMetric> affectedMetrics) {
		if (affectedMetrics.isEmpty()) {
			return List.of();
		}
		Set<String> affectedMetricKeys = affectedMetrics.stream().map(Enum::name).collect(Collectors.toSet());
		List<BadgeEntity> candidates = badgeRepository.findNotEarnedByAnyMetric(memberId, affectedMetricKeys);
		if (candidates.isEmpty()) {
			return List.of();
		}

		Map<UUID, List<BadgeConditionEntity>> conditionsByBadgeId = badgeConditionRepository
				.findByIdBadgeIdIn(candidates.stream().map(BadgeEntity::getId).toList()).stream()
				.collect(Collectors.groupingBy(condition -> condition.getId().badgeId()));

		Map<BadgeMetric, Long> metricValues = new EnumMap<>(BadgeMetric.class);
		List<AwardedBadgeResult> awarded = new ArrayList<>();
		for (BadgeEntity badge : candidates) {
			List<BadgeConditionEntity> conditions = conditionsByBadgeId.getOrDefault(badge.getId(), List.of());
			boolean achieved = !conditions.isEmpty() && conditions.stream().allMatch(condition -> {
				BadgeMetric metric = BadgeMetric.valueOf(condition.getId().metricKey());
				long value = metricValues.computeIfAbsent(metric, m -> providerRegistry.get(m).calculate(memberId));
				return value >= condition.getThreshold();
			});
			if (!achieved) {
				continue;
			}
			memberBadgeRepository.awardIfAbsent(memberId, badge.getId(), tripId).ifPresent(earnedAt -> {
				notificationCreationService.createBadgeAwarded(memberId, badge.getId(), badge.getName());
				awarded.add(new AwardedBadgeResult(badge.getId(), badge.getName(), badge.getImageKey(),
						badge.getConditionText(), earnedAt));
			});
		}
		return awarded;
	}

	/**
	 * 이번 activity 처리로 새로 지급된 배지의 semantic result다. 만료되는 Presigned URL 대신 image key를
	 * 담아 idempotency record에 그대로 저장할 수 있다.
	 */
	public record AwardedBadgeResult(UUID badgeId, String name, String imageKey, String condition, Instant earnedAt) {
	}
}
