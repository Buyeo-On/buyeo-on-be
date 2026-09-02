package com.buyeoon.place.application;

import com.buyeoon.place.entity.SavedPlaceId;
import com.buyeoon.place.repository.PlaceQueryRepository;
import com.buyeoon.place.repository.SavedPlaceRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceCommandService {

	private final PlaceQueryRepository placeQueryRepository;
	private final SavedPlaceRepository savedPlaceRepository;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public PlaceCommandService(PlaceQueryRepository placeQueryRepository,
			SavedPlaceRepository savedPlaceRepository) {
		this.placeQueryRepository = placeQueryRepository;
		this.savedPlaceRepository = savedPlaceRepository;
	}

	@Transactional
	public void savePlace(UUID memberId, UUID placeId) {
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
