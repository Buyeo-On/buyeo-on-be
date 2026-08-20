package com.buyeoon.point.application;

import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.member.application.ResourceNotFoundException;
import com.buyeoon.point.PointRewardService;
import com.buyeoon.point.entity.PointTransactionType;
import com.buyeoon.point.repository.PointTransactionRepository;
import com.buyeoon.trip.TripQueryService;
import com.buyeoon.trip.entity.TripStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 여행 종료 후 처리 방법을 선택하기 전에 정산 대상 포인트를 미리 보여주는 point 도메인의 공개 seam이다. */
@Service
@Transactional
public class PointSettlementPreviewService {

	private static final int CARRY_OVER_DURATION_HOURS = 240;

	private final TripQueryService tripQueryService;
	private final PointRewardService pointRewardService;
	private final PointTransactionRepository pointTransactionRepository;

	public PointSettlementPreviewService(TripQueryService tripQueryService, PointRewardService pointRewardService,
			PointTransactionRepository pointTransactionRepository) {
		this.tripQueryService = tripQueryService;
		this.pointRewardService = pointRewardService;
		this.pointTransactionRepository = pointTransactionRepository;
	}

	/**
	 * 활성 회원 행을 잠그고 만료 도래분을 반영한 전체 잔액을 조회한 뒤, 본인이 소유한 종료된 여행의 정산 대상 포인트를 계산한다. 여행이
	 * 없거나 본인 소유가 아니면 {@link ResourceNotFoundException}을, 진행 중이거나 이미 정산됐으면
	 * {@link InvalidStateTransitionException}을 던진다.
	 */
	public PointSettlementPreviewView preview(UUID memberId, UUID tripId) {
		long currentBalance = pointRewardService.currentBalance(memberId);
		TripStatus tripStatus = tripQueryService.findOwnedTripStatus(memberId, tripId)
				.orElseThrow(ResourceNotFoundException::new);
		if (tripStatus != TripStatus.ENDED) {
			throw new InvalidStateTransitionException();
		}
		long settleablePoints = pointTransactionRepository.sumAmountByTripIdAndType(tripId, PointTransactionType.EARN);
		return new PointSettlementPreviewView(tripId, settleablePoints, currentBalance, CARRY_OVER_DURATION_HOURS);
	}

	public record PointSettlementPreviewView(UUID tripId, long settleablePoints, long currentBalance,
			int carryOverDurationHours) {
	}
}
