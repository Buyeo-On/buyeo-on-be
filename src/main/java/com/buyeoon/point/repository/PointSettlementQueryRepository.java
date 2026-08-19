package com.buyeoon.point.repository;

import com.buyeoon.point.entity.PointSettlementEntity;
import com.buyeoon.point.entity.SettlementChoice;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

	/**
	 * 회원이 `부여에 남기기`를 선택하고 양수 포인트를 정산한 건수를 센다({@code POINT_DONATION_COUNT},
	 * ADR-003).
	 */
	@Query("""
			SELECT COUNT(s) FROM PointSettlementEntity s
			JOIN TripEntity t ON t.id = s.tripId
			WHERE t.memberId = :memberId
			  AND s.choice = :choice
			  AND s.settledPoints > 0
			""")
	long countPositiveDonationsByMemberId(@Param("memberId") UUID memberId, @Param("choice") SettlementChoice choice);

	/**
	 * 회원의 양수 포인트 기부 정산을 정산 시각 내림차순, 여행 ID 오름차순으로 조회한다. Reconciliation의 최근 기여 trip
	 * tie-breaker(ADR-003)에 사용하며 {@code pageable}로 최근 1건만 조회한다.
	 */
	@Query("""
			SELECT s FROM PointSettlementEntity s
			JOIN TripEntity t ON t.id = s.tripId
			WHERE t.memberId = :memberId
			  AND s.choice = :choice
			  AND s.settledPoints > 0
			ORDER BY s.settledAt DESC, s.tripId ASC
			""")
	List<PointSettlementEntity> findPositiveDonationsByMemberIdOrderBySettledAtDescTripIdAsc(
			@Param("memberId") UUID memberId, @Param("choice") SettlementChoice choice, Pageable pageable);
}
