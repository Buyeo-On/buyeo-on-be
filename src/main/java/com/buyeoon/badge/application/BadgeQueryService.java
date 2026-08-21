package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.badge.BadgeStatus;
import com.buyeoon.badge.entity.BadgeCategory;
import com.buyeoon.badge.entity.BadgeConditionEntity;
import com.buyeoon.badge.entity.BadgeEntity;
import com.buyeoon.badge.entity.MemberBadgeEntity;
import com.buyeoon.badge.repository.BadgeConditionRepository;
import com.buyeoon.badge.repository.BadgeRepository;
import com.buyeoon.badge.repository.MemberBadgeRepository;
import com.buyeoon.common.storage.PublicImageUrlService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로그인 회원의 배지 진척도 목록을 조회하는 application service다(UC-18). */
@Service
@Transactional(readOnly = true)
public class BadgeQueryService {

	private final BadgeRepository badgeRepository;
	private final BadgeConditionRepository badgeConditionRepository;
	private final MemberBadgeRepository memberBadgeRepository;
	private final BadgeMetricProviderRegistry providerRegistry;
	private final PublicImageUrlService imageUrls;

	public BadgeQueryService(BadgeRepository badgeRepository, BadgeConditionRepository badgeConditionRepository,
			MemberBadgeRepository memberBadgeRepository, BadgeMetricProviderRegistry providerRegistry,
			PublicImageUrlService imageUrls) {
		this.badgeRepository = badgeRepository;
		this.badgeConditionRepository = badgeConditionRepository;
		this.memberBadgeRepository = memberBadgeRepository;
		this.providerRegistry = providerRegistry;
		this.imageUrls = imageUrls;
	}

	/**
	 * 지급 중단됐고 아직 획득하지 않은 배지를 제외한 진척도 목록을 category enum 순서, 같은 분야 내 badge ID 오름차순으로
	 * 반환한다. category가 주어지면 목록과 개수를 필터링된 결과 기준으로 계산한다. 같은 메트릭은 요청 안에서 한 번만 계산해 중복
	 * 계산을 막는다.
	 */
	public BadgeListView list(UUID memberId, BadgeCategory category) {
		List<BadgeEntity> badges = badgeRepository.findVisibleForMember(memberId);
		if (category != null) {
			badges = badges.stream().filter(badge -> badge.getCategory() == category).toList();
		}
		if (badges.isEmpty()) {
			return new BadgeListView(0, 0, List.of());
		}

		List<UUID> badgeIds = badges.stream().map(BadgeEntity::getId).toList();
		Map<UUID, List<BadgeConditionEntity>> conditionsByBadgeId = badgeConditionRepository.findByIdBadgeIdIn(badgeIds)
				.stream().collect(Collectors.groupingBy(condition -> condition.getId().badgeId()));
		Map<UUID, Instant> earnedAtByBadgeId = memberBadgeRepository.findByIdMemberId(memberId).stream().collect(
				Collectors.toMap(memberBadge -> memberBadge.getId().badgeId(), MemberBadgeEntity::getEarnedAt));

		Map<BadgeMetric, Long> metricValues = new EnumMap<>(BadgeMetric.class);
		List<BadgeItemView> items = badges.stream()
				.map(badge -> toView(badge, conditionsByBadgeId.getOrDefault(badge.getId(), List.of()),
						earnedAtByBadgeId.get(badge.getId()), memberId, metricValues))
				.toList();
		long earnedCount = items.stream().filter(item -> item.status() == BadgeStatus.EARNED).count();
		return new BadgeListView((int) earnedCount, items.size(), items);
	}

	private BadgeItemView toView(BadgeEntity badge, List<BadgeConditionEntity> conditions, Instant earnedAt,
			UUID memberId, Map<BadgeMetric, Long> metricValues) {
		List<BadgeConditionView> conditionViews = conditions.stream()
				.map(condition -> toConditionView(condition, memberId, metricValues)).toList();
		BadgeStatus status = status(earnedAt, conditionViews);
		String imageUrl = badge.getImageKey() != null ? imageUrls.create(badge.getImageKey()) : null;
		return new BadgeItemView(badge.getId(), badge.getCategory(), badge.getName(), badge.getDescription(), imageUrl,
				badge.getConditionText(), conditionViews, status, earnedAt);
	}

	private BadgeConditionView toConditionView(BadgeConditionEntity condition, UUID memberId,
			Map<BadgeMetric, Long> metricValues) {
		BadgeMetric metric = BadgeMetric.valueOf(condition.getId().metricKey());
		long currentValue = metricValues.computeIfAbsent(metric,
				m -> providerRegistry.get(m).calculate(memberId).value());
		long threshold = condition.getThreshold();
		long progress = Math.min(currentValue, threshold);
		return new BadgeConditionView(metric, progress, threshold, progress >= threshold);
	}

	private BadgeStatus status(Instant earnedAt, List<BadgeConditionView> conditions) {
		if (earnedAt != null) {
			return BadgeStatus.EARNED;
		}
		boolean anyProgress = conditions.stream().anyMatch(condition -> condition.progress() > 0);
		return anyProgress ? BadgeStatus.IN_PROGRESS : BadgeStatus.NOT_EARNED;
	}

	public record BadgeListView(int earnedCount, int totalCount, List<BadgeItemView> items) {
		public BadgeListView {
			items = List.copyOf(items);
		}
	}

	public record BadgeItemView(UUID badgeId, BadgeCategory category, String name, String description, String imageUrl,
			String condition, List<BadgeConditionView> conditions, BadgeStatus status, Instant earnedAt) {
		public BadgeItemView {
			conditions = List.copyOf(conditions);
		}
	}

	public record BadgeConditionView(BadgeMetric metricKey, long progress, long threshold, boolean achieved) {
	}
}
