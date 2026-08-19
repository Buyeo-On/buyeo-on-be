package com.buyeoon.point;

import com.buyeoon.point.entity.PointTransactionEntity;
import com.buyeoon.point.entity.PointTransactionType;
import com.buyeoon.point.repository.PointTransactionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 다른 도메인이 미션 완료 보상을 지급할 때 사용하는 point 도메인의 공개 seam이다. */
@Service
public class PointRewardService {

	private final PointTransactionRepository pointTransactions;

	/** 포인트 내역 저장과 잔액 조회를 담당하는 통합 repository를 주입받는다. */
	public PointRewardService(PointTransactionRepository pointTransactions) {
		this.pointTransactions = pointTransactions;
	}

	/** 미션 완료 보상을 지급하고 회원의 갱신된 포인트 잔액을 반환한다. */
	public long reward(UUID memberId, UUID tripId, UUID participationId, int amount, String description) {
		pointTransactions.save(PointTransactionEntity.create(memberId, tripId, participationId,
				PointTransactionType.EARN, amount, description));
		return currentBalance(memberId);
	}

	/** 회원의 현재 포인트 잔액을 포인트 내역 합계로 계산한다. */
	public long currentBalance(UUID memberId) {
		return pointTransactions.sumAmountByMemberId(memberId);
	}
}
