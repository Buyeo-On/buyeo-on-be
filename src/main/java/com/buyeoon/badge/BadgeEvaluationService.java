package com.buyeoon.badge;

import com.buyeoon.badge.application.BadgeMetricProviderRegistry;
import com.buyeoon.badge.application.BadgeMetricSnapshot;
import com.buyeoon.badge.entity.BadgeConditionEntity;
import com.buyeoon.badge.entity.BadgeEntity;
import com.buyeoon.badge.repository.BadgeConditionRepository;
import com.buyeoon.badge.repository.BadgeRepository;
import com.buyeoon.badge.repository.MemberBadgeRepository;
import com.buyeoon.notification.NotificationCreationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
	 *
	 * <p>
	 * 이번 호출에서 배지가 실제로 지급되면 {@code BADGE_ACQUIRED_COUNT}가 함께 바뀌므로, 그 metric에 걸린 meta
	 * 배지(예: 전체 배지 달성률 배지)도 놓치지 않도록 새 지급이 더는 없을 때까지 같은 transaction에서
	 * {@code BADGE_ACQUIRED_COUNT}를 대상으로 재판정한다(ADR-003).
	 */
	@Transactional
	public List<AwardedBadgeResult> award(UUID memberId, UUID tripId, Set<BadgeMetric> affectedMetrics) {
		List<AwardedBadgeResult> awarded = new ArrayList<>();
		Set<BadgeMetric> pendingMetrics = affectedMetrics;
		while (!pendingMetrics.isEmpty()) {
			List<AwardedBadgeResult> awardedThisRound = awardForMetrics(memberId, tripId, pendingMetrics);
			if (awardedThisRound.isEmpty()) {
				break;
			}
			awarded.addAll(awardedThisRound);
			pendingMetrics = Set.of(BadgeMetric.BADGE_ACQUIRED_COUNT);
		}
		// 각 round는 자체적으로 badge ID 오름차순이지만, BADGE_ACQUIRED_COUNT를 조건으로 쓰는 배지가 다른
		// 배지보다 낮은 ID를 가질 수도 있어 round를 넘나드는 전역 순서는 다시 정렬해야 보장된다. PostgreSQL의
		// UUID 오름차순은 원시 바이트 비교이며 이는 표준 문자열 표현의 사전식 비교와 같으므로, 부호 있는
		// long으로 비교하는 UUID.compareTo 대신 문자열로 비교해 repository의 `ORDER BY id ASC`와 순서를
		// 맞춘다.
		awarded.sort(Comparator.comparing(result -> result.badgeId().toString()));
		return awarded;
	}

	private List<AwardedBadgeResult> awardForMetrics(UUID memberId, UUID tripId, Set<BadgeMetric> affectedMetrics) {
		Set<String> affectedMetricKeys = affectedMetrics.stream().map(Enum::name).collect(Collectors.toSet());
		List<BadgeEntity> candidates = badgeRepository.findNotEarnedByAnyMetric(memberId, affectedMetricKeys);
		if (candidates.isEmpty()) {
			return List.of();
		}

		Map<UUID, List<BadgeConditionEntity>> conditionsByBadgeId = badgeConditionRepository
				.findByIdBadgeIdIn(candidates.stream().map(BadgeEntity::getId).toList()).stream()
				.collect(Collectors.groupingBy(condition -> condition.getId().badgeId()));

		Map<BadgeMetric, BadgeMetricSnapshot> metricSnapshots = new EnumMap<>(BadgeMetric.class);
		List<AwardedBadgeResult> awarded = new ArrayList<>();
		for (BadgeEntity badge : candidates) {
			List<BadgeConditionEntity> conditions = conditionsByBadgeId.getOrDefault(badge.getId(), List.of());
			boolean achieved = !conditions.isEmpty() && conditions.stream().allMatch(condition -> {
				BadgeMetric metric = BadgeMetric.valueOf(condition.getId().metricKey());
				long value = metricSnapshots.computeIfAbsent(metric, m -> providerRegistry.get(m).calculate(memberId))
						.value();
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
