package com.buyeoon.point;

import com.buyeoon.point.entity.PointSettlementEntity;
import com.buyeoon.point.entity.SettlementChoice;
import com.buyeoon.point.repository.PointSettlementQueryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * badge 도메인이 {@code POINT_DONATION_COUNT}를 판정할 때 사용하는 point 도메인의 공개 query
 * seam이다(ADR-003).
 */
@Service
@Transactional(readOnly = true)
public class PointDonationMetricQuery {

	private final PointSettlementQueryRepository pointSettlements;

	public PointDonationMetricQuery(PointSettlementQueryRepository pointSettlements) {
		this.pointSettlements = pointSettlements;
	}

	/**
	 * 회원이 `LEAVE_TO_BUYEO`를 선택하고 `settled_points > 0`으로 확정한 정산 수와 가장 최근 정산의 여행·시각을
	 * 계산한다. `CARRY_OVER`와 0 point 정산은 포함하지 않는다. 해당 정산이 없으면 여행·시각은 {@code null}이다.
	 */
	public PointDonationMetricSnapshot snapshot(UUID memberId) {
		long count = pointSettlements.countPositiveDonationsByMemberId(memberId, SettlementChoice.LEAVE_TO_BUYEO);
		if (count == 0) {
			return new PointDonationMetricSnapshot(0, null, null);
		}
		List<PointSettlementEntity> latest = pointSettlements
				.findPositiveDonationsByMemberIdOrderBySettledAtDescTripIdAsc(memberId, SettlementChoice.LEAVE_TO_BUYEO,
						PageRequest.of(0, 1));
		PointSettlementEntity mostRecent = latest.getFirst();
		return new PointDonationMetricSnapshot(count, mostRecent.getTripId(), mostRecent.getSettledAt());
	}
}
