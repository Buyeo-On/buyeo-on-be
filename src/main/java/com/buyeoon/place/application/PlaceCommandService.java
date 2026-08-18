package com.buyeoon.place.application;

import com.buyeoon.place.entity.SavedPlaceEntity;
import com.buyeoon.place.entity.SavedPlaceId;
import com.buyeoon.place.repository.PlaceQueryRepository;
import com.buyeoon.place.repository.SavedPlaceRepository;
import com.buyeoon.trip.TripQueryService;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceCommandService {

	private final PlaceQueryRepository placeQueryRepository;
	private final SavedPlaceRepository savedPlaceRepository;
	private final TripQueryService tripQueryService;

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
		try {
			savedPlaceRepository.save(SavedPlaceEntity.create(memberId, placeId));
			savedPlaceRepository.flush();
		} catch (DataIntegrityViolationException alreadySaved) {
			// 회원-장소 유니크 제약 위반은 이미 저장된 상태라는 뜻이므로 성공으로 흡수한다.
		}
	}

	@Transactional
	public void deleteSavedPlace(UUID memberId, UUID placeId) {
		savedPlaceRepository.deleteById(new SavedPlaceId(memberId, placeId));
	}
}
