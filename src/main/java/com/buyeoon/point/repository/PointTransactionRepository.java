package com.buyeoon.point.repository;

import com.buyeoon.point.entity.PointTransactionEntity;
import com.buyeoon.point.entity.PointTransactionType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 회원의 포인트 잔액·누적 적립 합계를 계산하는 조회 전용 repository다. */
public interface PointTransactionRepository extends JpaRepository<PointTransactionEntity, UUID> {

	/** 회원의 포인트 잔액(전체 내역 amount 합계)을 계산한다. 내역이 없으면 0을 반환한다. */
	@Query("SELECT COALESCE(SUM(t.amount), 0) FROM PointTransactionEntity t WHERE t.memberId = :memberId")
	long sumAmountByMemberId(@Param("memberId") UUID memberId);

	/** 회원의 특정 유형 포인트 내역 amount 합계를 계산한다. 내역이 없으면 0을 반환한다. */
	@Query("SELECT COALESCE(SUM(t.amount), 0) FROM PointTransactionEntity t "
			+ "WHERE t.memberId = :memberId AND t.type = :type")
	long sumAmountByMemberIdAndType(@Param("memberId") UUID memberId, @Param("type") PointTransactionType type);
}
