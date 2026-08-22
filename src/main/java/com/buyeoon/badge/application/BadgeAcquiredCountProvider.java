package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.badge.entity.MemberBadgeEntity;
import com.buyeoon.badge.repository.MemberBadgeRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 회원이 획득한 배지 수를 세는 meta Provider다(ADR-003). 판정 대상 배지 자신은 판정 시점에 아직
 * {@code member_badges}에 존재하지 않으므로 값 계산에서 자기 자신을 참조하는 순환이 생기지 않는다.
 */
@Component
public class BadgeAcquiredCountProvider implements BadgeMetricProvider {

	private final MemberBadgeRepository memberBadgeRepository;

	public BadgeAcquiredCountProvider(MemberBadgeRepository memberBadgeRepository) {
		this.memberBadgeRepository = memberBadgeRepository;
	}

	@Override
	public BadgeMetric metric() {
		return BadgeMetric.BADGE_ACQUIRED_COUNT;
	}

	@Override
	public BadgeMetricSnapshot calculate(UUID memberId) {
		List<MemberBadgeEntity> earnedBadges = memberBadgeRepository.findByIdMemberId(memberId);
		if (earnedBadges.isEmpty()) {
			return new BadgeMetricSnapshot(0, null, null);
		}
		MemberBadgeEntity latest = earnedBadges.stream().max(Comparator.comparing(MemberBadgeEntity::getEarnedAt))
				.orElseThrow();
		return new BadgeMetricSnapshot(earnedBadges.size(), latest.getTripId(), latest.getEarnedAt());
	}
}
