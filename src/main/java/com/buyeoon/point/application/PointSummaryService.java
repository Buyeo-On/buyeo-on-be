package com.buyeoon.point.application;

import com.buyeoon.point.entity.PointSettlementEntity;
import com.buyeoon.point.entity.PointTransactionType;
import com.buyeoon.point.entity.SettlementChoice;
import com.buyeoon.point.repository.PointSettlementQueryRepository;
import com.buyeoon.point.repository.PointTransactionRepository;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원의 포인트 잔액 요약(잔액, 누적 적립, 만료 예정)을 조회하는 point 도메인의 공개 seam이다. */
@Service
@Transactional
public class PointSummaryService {

	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final PointTransactionRepository pointTransactionRepository;
	private final PointSettlementQueryRepository pointSettlementQueryRepository;
	private final PointExpirationService pointExpirationService;

	/** 포인트 조회 repository와 요청 시점 만료 확정 seam을 주입받는다. */
	public PointSummaryService(PointTransactionRepository pointTransactionRepository,
			PointSettlementQueryRepository pointSettlementQueryRepository,
			PointExpirationService pointExpirationService) {
		this.pointTransactionRepository = pointTransactionRepository;
		this.pointSettlementQueryRepository = pointSettlementQueryRepository;
		this.pointExpirationService = pointExpirationService;
	}

	/** 잔액, `EARN` 누적 합계, 만료 시각 오름차순 만료 예정 목록을 조회한다. */
	public PointSummaryView getSummary(UUID memberId) {
		pointExpirationService.expireDueSettlementsForActiveMember(memberId);
		long balance = pointTransactionRepository.sumAmountByMemberId(memberId);
		long cumulativeEarned = pointTransactionRepository.sumAmountByMemberIdAndType(memberId,
				PointTransactionType.EARN);
		List<PointExpirationView> expirations = pointSettlementQueryRepository
				.findActiveCarryOversByMemberId(memberId, SettlementChoice.CARRY_OVER).stream()
				.map(this::toExpirationView).toList();
		return new PointSummaryView(balance, cumulativeEarned, expirations);
	}

	private PointExpirationView toExpirationView(PointSettlementEntity settlement) {
		return new PointExpirationView(settlement.getSettledPoints(), settlement.getExpiresAt().atZone(ASIA_SEOUL));
	}

	public record PointSummaryView(long balance, long cumulativeEarned, List<PointExpirationView> expirations) {
		public PointSummaryView {
			expirations = List.copyOf(expirations);
		}
	}

	public record PointExpirationView(long points, ZonedDateTime expiresAt) {
	}
}
