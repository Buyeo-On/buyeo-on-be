package com.buyeoon.trip;

import com.buyeoon.trip.entity.TripStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인이 여행 상태를 확인할 때 사용하는 trip 도메인의 공개 seam이다. */
@Service
@Transactional(readOnly = true)
public class TripQueryService {

	private final TripRepository tripRepository;

	public TripQueryService(TripRepository tripRepository) {
		this.tripRepository = tripRepository;
	}

	public boolean hasActiveTrip(UUID memberId) {
		return tripRepository.findByMemberIdAndStatus(memberId, TripStatus.IN_PROGRESS).isPresent();
	}
}
