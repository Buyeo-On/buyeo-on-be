package com.buyeoon.badge;

import com.buyeoon.badge.application.BadgeMetricProviderRegistry;
import com.buyeoon.badge.application.BadgeMetricSnapshot;
import com.buyeoon.badge.entity.BadgeConditionEntity;
import com.buyeoon.badge.entity.BadgeEntity;
import com.buyeoon.badge.repository.BadgeConditionRepository;
import com.buyeoon.badge.repository.BadgeMemberRepository;
import com.buyeoon.badge.repository.BadgeRepository;
import com.buyeoon.badge.repository.MemberBadgeRepository;
import com.buyeoon.member.entity.MemberStatus;
import com.buyeoon.notification.NotificationCreationService;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 새 badge catalog 도입 전에 과거 활동으로 조건을 충족한 active member에게 daily reconciliation으로
 * 배지를 지급하는 badge 도메인의 Scheduler entrypoint다(ADR-003). 회원별 transaction에서 member
 * row를 먼저 잠그고 {@code ACTIVE} 상태를 다시 확인한 뒤 판정하며, 한 회원의 실패는 다른 회원 처리를 막지 않고 다음
 * 실행에서 다시 판정한다.
 */
@Service
public class BadgeReconciliationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(BadgeReconciliationService.class);
	private static final int PAGE_SIZE = 200;
	private static final Set<String> ALL_METRIC_KEYS = EnumSet.allOf(BadgeMetric.class).stream().map(Enum::name)
			.collect(Collectors.toUnmodifiableSet());

	private final BadgeMemberRepository members;
	private final BadgeMetricProviderRegistry providerRegistry;
	private final BadgeRepository badgeRepository;
	private final BadgeConditionRepository badgeConditionRepository;
	private final MemberBadgeRepository memberBadgeRepository;
	private final NotificationCreationService notificationCreationService;
	private final TransactionTemplate transactions;

	public BadgeReconciliationService(BadgeMemberRepository members, BadgeMetricProviderRegistry providerRegistry,
			BadgeRepository badgeRepository, BadgeConditionRepository badgeConditionRepository,
			MemberBadgeRepository memberBadgeRepository, NotificationCreationService notificationCreationService,
			PlatformTransactionManager transactionManager) {
		this.members = members;
		this.providerRegistry = providerRegistry;
		this.badgeRepository = badgeRepository;
		this.badgeConditionRepository = badgeConditionRepository;
		this.memberBadgeRepository = memberBadgeRepository;
		this.notificationCreationService = notificationCreationService;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** 활성 회원을 대상으로 reconciliation을 실행하고 새로 배지를 받은 회원 수를 반환한다. */
	public int reconcile() {
		int membersAwarded = 0;
		int page = 0;
		while (true) {
			List<UUID> memberIds = members.findIdsByStatus(MemberStatus.ACTIVE, PageRequest.of(page, PAGE_SIZE));
			if (memberIds.isEmpty()) {
				return membersAwarded;
			}
			for (UUID memberId : memberIds) {
				if (reconcileMember(memberId)) {
					membersAwarded++;
				}
			}
			page++;
		}
	}

	private boolean reconcileMember(UUID memberId) {
		try {
			return Boolean.TRUE.equals(transactions.execute(status -> reconcileMemberInTransaction(memberId)));
		} catch (RuntimeException exception) {
			LOGGER.warn("배지 reconciliation에 실패했습니다. memberId={}", memberId, exception);
			return false;
		}
	}

	private boolean reconcileMemberInTransaction(UUID memberId) {
		if (members.findByIdAndStatus(memberId, MemberStatus.ACTIVE).isEmpty()) {
			return false;
		}

		List<BadgeEntity> candidates = badgeRepository.findNotEarnedByAnyMetric(memberId, ALL_METRIC_KEYS);
		if (candidates.isEmpty()) {
			return false;
		}

		Map<UUID, List<BadgeConditionEntity>> conditionsByBadgeId = badgeConditionRepository
				.findByIdBadgeIdIn(candidates.stream().map(BadgeEntity::getId).toList()).stream()
				.collect(Collectors.groupingBy(condition -> condition.getId().badgeId()));

		Map<BadgeMetric, BadgeMetricSnapshot> metricSnapshots = new EnumMap<>(BadgeMetric.class);
		boolean awardedAny = false;
		for (BadgeEntity badge : candidates) {
			List<BadgeConditionEntity> conditions = conditionsByBadgeId.getOrDefault(badge.getId(), List.of());
			if (conditions.isEmpty()) {
				continue;
			}
			boolean achieved = conditions.stream().allMatch(condition -> {
				BadgeMetric metric = BadgeMetric.valueOf(condition.getId().metricKey());
				BadgeMetricSnapshot snapshot = metricSnapshots.computeIfAbsent(metric,
						m -> providerRegistry.get(m).calculate(memberId));
				return snapshot.value() >= condition.getThreshold();
			});
			if (!achieved) {
				continue;
			}

			UUID earningTripId = earningTripId(conditions, metricSnapshots);
			if (memberBadgeRepository.awardIfAbsent(memberId, badge.getId(), earningTripId).isPresent()) {
				notificationCreationService.createBadgeAwarded(memberId, badge.getId(), badge.getName());
				awardedAny = true;
			}
		}
		return awardedAny;
	}

	/**
	 * 배지의 모든 조건에 기여한 활동 중 {@code latestContributionAt} 내림차순, metric key 오름차순, trip
	 * ID 오름차순으로 정렬한 첫 활동의 여행을 반환한다(ADR-003).
	 */
	private UUID earningTripId(List<BadgeConditionEntity> conditions,
			Map<BadgeMetric, BadgeMetricSnapshot> metricSnapshots) {
		Comparator<BadgeConditionEntity> tieBreak = Comparator
				.comparing((BadgeConditionEntity condition) -> metricSnapshots
						.get(BadgeMetric.valueOf(condition.getId().metricKey())).latestContributionAt())
				.reversed().thenComparing(condition -> condition.getId().metricKey())
				.thenComparing(condition -> metricSnapshots.get(BadgeMetric.valueOf(condition.getId().metricKey()))
						.latestContributingTripId());
		BadgeConditionEntity earliestSortedCondition = conditions.stream().min(tieBreak)
				.orElseThrow(() -> new IllegalStateException("배지 조건이 없습니다."));
		return metricSnapshots.get(BadgeMetric.valueOf(earliestSortedCondition.getId().metricKey()))
				.latestContributingTripId();
	}
}
