package com.buyeoon.badge.repository;

import com.buyeoon.badge.entity.BadgeEntity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BadgeRepository extends JpaRepository<BadgeEntity, UUID> {

	/** 지급이 중단되지 않아 startup catalog 검증 대상인 배지를 조회한다. */
	List<BadgeEntity> findByRetiredAtIsNull();

	/**
	 * 배지 진척도 목록에 노출할 배지를 조회한다. 지급이 중단됐고 아직 획득하지 않은 배지는 제외하며, 이미 획득한 배지는 지급 중단 여부와
	 * 무관하게 포함한다. category enum 순서, 같은 분야 내에서는 badge ID 오름차순으로 정렬한다.
	 */
	@Query("""
			SELECT b FROM BadgeEntity b
			WHERE b.retiredAt IS NULL
			   OR EXISTS (SELECT 1 FROM MemberBadgeEntity mb WHERE mb.id.badgeId = b.id AND mb.id.memberId = :memberId)
			ORDER BY b.category ASC, b.id ASC
			""")
	List<BadgeEntity> findVisibleForMember(@Param("memberId") UUID memberId);

	/**
	 * 배지 상세에 노출할 단건 배지를 조회한다. 지급이 중단됐고 아직 획득하지 않은 배지면 빈 값을 반환한다.
	 */
	@Query("""
			SELECT b FROM BadgeEntity b
			WHERE b.id = :badgeId
			  AND (b.retiredAt IS NULL
			   OR EXISTS (SELECT 1 FROM MemberBadgeEntity mb WHERE mb.id.badgeId = b.id AND mb.id.memberId = :memberId))
			""")
	Optional<BadgeEntity> findVisibleForMember(@Param("memberId") UUID memberId, @Param("badgeId") UUID badgeId);

	/**
	 * 지급이 중단되지 않았고 주어진 메트릭 중 하나 이상을 조건으로 가지며 회원이 아직 획득하지 않은 배지를 badge ID 오름차순으로
	 * 조회한다.
	 */
	@Query("""
			SELECT b FROM BadgeEntity b
			WHERE b.retiredAt IS NULL
			  AND EXISTS (SELECT 1 FROM BadgeConditionEntity c WHERE c.id.badgeId = b.id AND c.id.metricKey IN :metricKeys)
			  AND NOT EXISTS (
			      SELECT 1 FROM MemberBadgeEntity mb WHERE mb.id.badgeId = b.id AND mb.id.memberId = :memberId
			  )
			ORDER BY b.id ASC
			""")
	List<BadgeEntity> findNotEarnedByAnyMetric(@Param("memberId") UUID memberId,
			@Param("metricKeys") Set<String> metricKeys);
}
