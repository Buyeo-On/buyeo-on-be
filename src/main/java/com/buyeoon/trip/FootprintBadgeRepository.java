package com.buyeoon.trip;

import com.buyeoon.badge.entity.MemberBadgeEntity;
import com.buyeoon.badge.entity.MemberBadgeId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 발자취 조회가 해당 여행에서 처음 획득한 배지를 읽기 위한 trip 도메인 소유의 읽기 전용 리포지토리다. */
public interface FootprintBadgeRepository extends JpaRepository<MemberBadgeEntity, MemberBadgeId> {

	/** 해당 여행에서 처음 획득한 배지만 조회한다({@code member_badges.trip_id} 단일 조건, ADR-003). */
	@Query("""
			SELECT new com.buyeoon.trip.EarnedBadgeProjection(b, mb.earnedAt)
			FROM MemberBadgeEntity mb JOIN BadgeEntity b ON b.id = mb.id.badgeId
			WHERE mb.id.memberId = :memberId AND mb.tripId = :tripId
			ORDER BY mb.earnedAt ASC
			""")
	List<EarnedBadgeProjection> findBadgesByMemberIdAndTripId(@Param("memberId") UUID memberId,
			@Param("tripId") UUID tripId);
}
