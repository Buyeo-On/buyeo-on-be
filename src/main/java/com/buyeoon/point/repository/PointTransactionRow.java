package com.buyeoon.point.repository;

import com.buyeoon.point.entity.PointTransactionType;
import java.time.Instant;
import java.util.UUID;

/** balanceAfter 윈도우 함수 계산 결과를 담는 포인트 내역 목록 조회 전용 row다. */
public record PointTransactionRow(UUID id, PointTransactionType type, long amount, String description,
		Instant occurredAt, long balanceAfter) {

	static PointTransactionRow from(Object[] row) {
		return new PointTransactionRow((UUID) row[0], PointTransactionType.valueOf((String) row[1]),
				((Number) row[2]).longValue(), (String) row[3], (Instant) row[4], ((Number) row[5]).longValue());
	}
}
