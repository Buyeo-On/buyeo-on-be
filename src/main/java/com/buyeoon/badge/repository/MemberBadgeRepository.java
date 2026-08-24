package com.buyeoon.badge.repository;

import com.buyeoon.badge.entity.MemberBadgeEntity;
import com.buyeoon.badge.entity.MemberBadgeId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberBadgeRepository extends JpaRepository<MemberBadgeEntity, MemberBadgeId> {

	/** 회원이 획득한 모든 배지 이력을 조회한다. */
	List<MemberBadgeEntity> findByIdMemberId(UUID memberId);

	long countByIdMemberId(UUID memberId);

	/** 회원이 특정 배지를 획득했는지와 획득 시각을 조회한다. */
	Optional<MemberBadgeEntity> findByIdMemberIdAndIdBadgeId(UUID memberId, UUID badgeId);

	/**
	 * {@code (member_id, badge_id)} 유일성으로 중복 획득을 막는다(ADR-003). 실제로 새 이력을 만든 경우에만
	 * DB가 기록한 획득 시각을 반환하고, 이미 획득한 배지면 빈 값을 반환한다.
	 */
	@Query(value = "INSERT INTO member_badges (member_id, badge_id, trip_id) VALUES (:memberId, :badgeId, :tripId) "
			+ "ON CONFLICT (member_id, badge_id) DO NOTHING RETURNING earned_at", nativeQuery = true)
	Optional<Instant> awardIfAbsent(@Param("memberId") UUID memberId, @Param("badgeId") UUID badgeId,
			@Param("tripId") UUID tripId);
}
