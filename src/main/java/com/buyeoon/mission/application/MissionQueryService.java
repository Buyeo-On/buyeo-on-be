package com.buyeoon.mission.application;

import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionParticipationEntity;
import com.buyeoon.mission.entity.MissionStatus;
import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionQueryRepository;
import com.buyeoon.mission.repository.NearbyMissionProjection;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.trip.TripQueryService;
import com.buyeoon.trip.entity.TripStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MissionQueryService {

	private static final int PARTICIPATION_RADIUS_METERS = 100;

	private final MissionQueryRepository missionQueryRepository;
	private final TripQueryService tripQueryService;

	public MissionQueryService(MissionQueryRepository missionQueryRepository, TripQueryService tripQueryService) {
		this.missionQueryRepository = missionQueryRepository;
		this.tripQueryService = tripQueryService;
	}

	public MissionListView listNearby(UUID memberId, UUID tripId, double latitude, double longitude) {
		TripStatus status = tripQueryService.findOwnedTripStatus(memberId, tripId)
				.orElseThrow(TripNotFoundException::new);
		if (status != TripStatus.IN_PROGRESS) {
			throw new TripNotInProgressException();
		}

		List<MissionItemView> items = missionQueryRepository.findNearby(tripId, latitude, longitude).stream()
				.map(row -> toView(tripId, row)).toList();
		return new MissionListView(items);
	}

	private MissionItemView toView(UUID tripId, NearbyMissionProjection row) {
		MissionEntity mission = row.mission();
		PlaceEntity place = row.place();
		MissionParticipationEntity participation = row.participation();

		MissionStatus persistedStatus = participation == null ? MissionStatus.AVAILABLE : participation.getStatus();
		int attemptCount = participation == null ? 0 : participation.getAttemptCount();
		boolean withinParticipationRadius = row.distanceMeters() <= PARTICIPATION_RADIUS_METERS;

		MissionAvailability availability = switch (persistedStatus) {
			case COMPLETED -> MissionAvailability.COMPLETED;
			case EXHAUSTED -> MissionAvailability.EXHAUSTED;
			case AVAILABLE -> withinParticipationRadius ? MissionAvailability.AVAILABLE : MissionAvailability.LOCKED;
		};

		Integer maxAttempts = mission.getMaxAttempts();
		Integer remainingAttempts = maxAttempts == null
				? null
				: (persistedStatus == MissionStatus.AVAILABLE ? maxAttempts - attemptCount : 0);

		int distanceMeters = (int) Math.round(row.distanceMeters());

		return new MissionItemView(mission.getId(), tripId, place.getId(), place.getName(), place.getLocation().getY(),
				place.getLocation().getX(), distanceMeters, mission.getType(), mission.getTitle(),
				mission.getRewardPoints(), availability, PARTICIPATION_RADIUS_METERS, remainingAttempts);
	}

	public record MissionListView(List<MissionItemView> items) {
		public MissionListView {
			items = List.copyOf(items);
		}
	}

	public record MissionItemView(UUID missionId, UUID tripId, UUID placeId, String placeName, double latitude,
			double longitude, int distanceMeters, MissionType type, String title, int rewardPoints,
			MissionAvailability availability, int participationRadiusMeters, Integer remainingAttempts) {
	}
}
