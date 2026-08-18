package com.buyeoon.point;

import com.buyeoon.point.entity.PointTransactionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointTransactionRepository extends JpaRepository<PointTransactionEntity, UUID> {

	@Query("SELECT COALESCE(SUM(t.amount), 0) FROM PointTransactionEntity t WHERE t.memberId = :memberId")
	long sumByMemberId(@Param("memberId") UUID memberId);
}
