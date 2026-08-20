package com.buyeoon.point.repository;

import com.buyeoon.point.entity.PointTransactionEntity;
import com.buyeoon.point.entity.PointTransactionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 회원의 포인트 내역을 저장하고 잔액·누적 적립 합계와 내역 목록을 조회하는 repository다. */
public interface PointTransactionRepository extends JpaRepository<PointTransactionEntity, UUID> {

	/** 회원의 포인트 잔액(전체 내역 amount 합계)을 계산한다. 내역이 없으면 0을 반환한다. */
	@Query("SELECT COALESCE(SUM(t.amount), 0) FROM PointTransactionEntity t WHERE t.memberId = :memberId")
	long sumAmountByMemberId(@Param("memberId") UUID memberId);

	/** 회원의 특정 유형 포인트 내역 amount 합계를 계산한다. 내역이 없으면 0을 반환한다. */
	@Query("SELECT COALESCE(SUM(t.amount), 0) FROM PointTransactionEntity t "
			+ "WHERE t.memberId = :memberId AND t.type = :type")
	long sumAmountByMemberIdAndType(@Param("memberId") UUID memberId, @Param("type") PointTransactionType type);

	/** 특정 여행의 특정 유형 포인트 내역 amount 합계를 계산한다. 내역이 없으면 0을 반환한다. */
	@Query("SELECT COALESCE(SUM(t.amount), 0) FROM PointTransactionEntity t "
			+ "WHERE t.tripId = :tripId AND t.type = :type")
	long sumAmountByTripIdAndType(@Param("tripId") UUID tripId, @Param("type") PointTransactionType type);

	/** 회원의 첫 페이지 내역을 발생 시각 내림차순, 동일 시각은 내역 ID 내림차순으로 조회하며 balanceAfter를 함께 계산한다. */
	default List<PointTransactionRow> findFromStart(UUID memberId, int limit) {
		return findRowsFromStart(memberId, limit).stream().map(PointTransactionRow::from).toList();
	}

	/** 커서 이후의 내역을 같은 정렬 기준으로 조회하며 balanceAfter를 함께 계산한다. */
	default List<PointTransactionRow> findAfter(UUID memberId, Instant occurredAt, UUID transactionId, int limit) {
		return findRowsAfter(memberId, occurredAt, transactionId, limit).stream().map(PointTransactionRow::from)
				.toList();
	}

	@Query(value = """
			SELECT sub.id, sub.type::text, sub.amount, sub.description, sub.occurred_at, sub.balance_after
			FROM (
			    SELECT id, type, amount, description, occurred_at,
			           SUM(amount) OVER (ORDER BY occurred_at, id) AS balance_after
			    FROM point_transactions
			    WHERE member_id = :memberId
			) sub
			ORDER BY sub.occurred_at DESC, sub.id DESC
			LIMIT :limit
			""", nativeQuery = true)
	List<Object[]> findRowsFromStart(@Param("memberId") UUID memberId, @Param("limit") int limit);

	@Query(value = """
			SELECT sub.id, sub.type::text, sub.amount, sub.description, sub.occurred_at, sub.balance_after
			FROM (
			    SELECT id, type, amount, description, occurred_at,
			           SUM(amount) OVER (ORDER BY occurred_at, id) AS balance_after
			    FROM point_transactions
			    WHERE member_id = :memberId
			) sub
			WHERE sub.occurred_at < :occurredAt OR (sub.occurred_at = :occurredAt AND sub.id < :transactionId)
			ORDER BY sub.occurred_at DESC, sub.id DESC
			LIMIT :limit
			""", nativeQuery = true)
	List<Object[]> findRowsAfter(@Param("memberId") UUID memberId, @Param("occurredAt") Instant occurredAt,
			@Param("transactionId") UUID transactionId, @Param("limit") int limit);
}
