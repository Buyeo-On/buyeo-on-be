package com.buyeoon.point;

import com.buyeoon.point.entity.PointTransactionEntity;
import com.buyeoon.point.entity.PointTransactionType;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 다른 도메인이 미션 완료 보상을 지급할 때 사용하는 point 도메인의 공개 seam이다. */
@Service
public class PointRewardService {

	private final PointTransactionRepository pointTransactions;

	public PointRewardService(PointTransactionRepository pointTransactions) {
		this.pointTransactions = pointTransactions;
	}

	/** 미션 완료 보상을 지급하고 회원의 갱신된 포인트 잔액을 반환한다. */
	public long reward(UUID memberId, UUID tripId, UUID participationId, int amount, String description) {
		pointTransactions.save(PointTransactionEntity.create(memberId, tripId, participationId,
				PointTransactionType.EARN, amount, description));
		return currentBalance(memberId);
	}

	public long currentBalance(UUID memberId) {
		return pointTransactions.sumByMemberId(memberId);
	}
}
