package com.buyeoon.point;

import com.buyeoon.point.entity.PointTransactionEntity;
import com.buyeoon.point.entity.PointTransactionType;
import com.buyeoon.point.application.PointExpirationService;
import com.buyeoon.point.repository.PointTransactionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인이 미션 완료 보상을 지급할 때 사용하는 point 도메인의 공개 seam이다. */
@Service
public class PointRewardService {

	private final PointTransactionRepository pointTransactions;
	private final PointExpirationService pointExpirationService;

	/** 포인트 내역 repository와 요청 시점 만료 확정 seam을 주입받는다. */
	public PointRewardService(PointTransactionRepository pointTransactions,
			PointExpirationService pointExpirationService) {
		this.pointTransactions = pointTransactions;
		this.pointExpirationService = pointExpirationService;
	}

	/** 미션 완료 보상을 지급하고 회원의 갱신된 포인트 잔액을 반환한다. */
	@Transactional
	public long reward(UUID memberId, UUID tripId, UUID participationId, int amount, String description) {
		pointExpirationService.expireDueSettlementsForActiveMember(memberId);
		pointTransactions.save(PointTransactionEntity.create(memberId, tripId, participationId,
				PointTransactionType.EARN, amount, description));
		return pointTransactions.sumAmountByMemberId(memberId);
	}

	/** 활성 회원 행을 잠그고 만료 도래분을 반영한 현재 포인트 잔액을 계산한다. */
	@Transactional
	public long currentBalance(UUID memberId) {
		pointExpirationService.expireDueSettlementsForActiveMember(memberId);
		return pointTransactions.sumAmountByMemberId(memberId);
	}
}
