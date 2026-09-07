package com.buyeoon.point;

import com.buyeoon.point.entity.PointTransactionType;
import com.buyeoon.point.repository.PointTransactionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * trip 도메인이 여행 통계의 {@code earnedPoints}를 조회할 때 사용하는 point 도메인의 공개 query seam이다.
 * 해당 여행의 {@code EARN} 내역 합계만 반환하며 쓰기 모델을 변경하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class TripEarnedPointsQuery {

	private final PointTransactionRepository pointTransactions;

	/** 여행별 포인트 내역 합계를 조회하는 repository를 주입받는다. */
	public TripEarnedPointsQuery(PointTransactionRepository pointTransactions) {
		this.pointTransactions = pointTransactions;
	}

	/** 해당 여행의 {@code EARN} 내역 amount 합계를 반환한다. 내역이 없으면 0이다. */
	public long sumByTripId(UUID tripId) {
		return pointTransactions.sumAmountByTripIdAndType(tripId, PointTransactionType.EARN);
	}
}
