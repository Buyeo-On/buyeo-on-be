package com.buyeoon.trip;

import com.buyeoon.member.application.ResourceNotFoundException;
import com.buyeoon.trip.entity.TripEntity;
import com.buyeoon.trip.entity.TripStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** point 도메인이 여행 포인트 정산을 확정할 때 여행 상태를 잠그고 전이하기 위해 사용하는 trip 도메인의 공개 seam이다. */
@Service
public class TripSettlementService {

	private final TripRepository trips;

	public TripSettlementService(TripRepository trips) {
		this.trips = trips;
	}

	/** 회원이 소유한 여행 행을 잠그고 현재 상태를 반환한다. 없거나 다른 회원 소유면 404다. */
	@Transactional
	public TripStatus lockOwnedTripStatus(UUID memberId, UUID tripId) {
		TripEntity trip = trips.lockByIdAndMemberId(tripId, memberId).orElseThrow(ResourceNotFoundException::new);
		return trip.getStatus();
	}

	/** 앞서 잠근 여행을 정산 완료 상태로 전이하고 정산 완료 시각을 기록한다. */
	@Transactional
	public void settle(UUID tripId, Instant settledAt) {
		TripEntity trip = trips.findById(tripId)
				.orElseThrow(() -> new IllegalStateException("정산할 여행을 찾을 수 없습니다. tripId=" + tripId));
		trip.settle(settledAt);
	}
}
