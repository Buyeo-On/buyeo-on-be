package com.buyeoon.point.repository;

import com.buyeoon.point.entity.PointSettlementEntity;
import com.buyeoon.point.entity.SettlementChoice;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 정산의 소유 회원을 확인하기 위해 trip 도메인의 {@code TripEntity}를 ad-hoc join으로 참조한다(미션 도메인의
 * {@code MissionQueryRepository}와 동일한 조회 전용 패턴).
 */
public interface PointSettlementQueryRepository extends JpaRepository<PointSettlementEntity, UUID> {

	/** 회원이 `다음에 이어서 쓰기`로 이월했고 아직 만료되지 않은 정산을 만료 시각 오름차순으로 조회한다. */
	@Query("""
			SELECT s FROM PointSettlementEntity s
			JOIN TripEntity t ON t.id = s.tripId
			WHERE t.memberId = :memberId
			  AND s.choice = :choice
			  AND s.expiresAt > CURRENT_TIMESTAMP
			ORDER BY s.expiresAt ASC
			""")
	List<PointSettlementEntity> findActiveCarryOversByMemberId(@Param("memberId") UUID memberId,
			@Param("choice") SettlementChoice choice);
}
