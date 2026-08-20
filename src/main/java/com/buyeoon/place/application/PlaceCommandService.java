package com.buyeoon.place.application;

import com.buyeoon.place.entity.SavedPlaceId;
import com.buyeoon.place.repository.PlaceQueryRepository;
import com.buyeoon.place.repository.SavedPlaceRepository;
import com.buyeoon.trip.TripQueryService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceCommandService {

	private final PlaceQueryRepository placeQueryRepository;
	private final SavedPlaceRepository savedPlaceRepository;
	private final TripQueryService tripQueryService;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public PlaceCommandService(PlaceQueryRepository placeQueryRepository, SavedPlaceRepository savedPlaceRepository,
			TripQueryService tripQueryService) {
		this.placeQueryRepository = placeQueryRepository;
		this.savedPlaceRepository = savedPlaceRepository;
		this.tripQueryService = tripQueryService;
	}

	@Transactional
	public void savePlace(UUID memberId, UUID placeId) {
		if (!tripQueryService.hasActiveTrip(memberId)) {
			throw new ActiveTripRequiredException();
		}
		if (!placeQueryRepository.existsById(placeId)) {
			throw new PlaceNotFoundException();
		}
		savedPlaceRepository.insertIfAbsent(memberId, placeId);
	}

	@Transactional
	public void deleteSavedPlace(UUID memberId, UUID placeId) {
		savedPlaceRepository.deleteById(new SavedPlaceId(memberId, placeId));
	}
}
