package com.buyeoon.mission.application;

import com.buyeoon.mission.entity.MissionChoiceEntity;
import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionParticipationEntity;
import com.buyeoon.mission.entity.MissionStatus;
import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionChoiceRepository;
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
	private final MissionChoiceRepository missionChoiceRepository;
	private final TripQueryService tripQueryService;

	public MissionQueryService(MissionQueryRepository missionQueryRepository,
			MissionChoiceRepository missionChoiceRepository, TripQueryService tripQueryService) {
		this.missionQueryRepository = missionQueryRepository;
		this.missionChoiceRepository = missionChoiceRepository;
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

	public Object getMission(UUID memberId, UUID missionId, UUID tripId, double latitude, double longitude) {
		TripStatus status = tripQueryService.findOwnedTripStatus(memberId, tripId)
				.orElseThrow(TripNotFoundException::new);
		if (status != TripStatus.IN_PROGRESS) {
			throw new TripNotInProgressException();
		}

		NearbyMissionProjection row = missionQueryRepository.findDetail(missionId, tripId, latitude, longitude)
				.orElseThrow(MissionNotFoundException::new);
		MissionCommon common = computeCommon(row);

		if (!common.withinParticipationRadius()) {
			return toRestrictedView(tripId, common);
		}
		if (common.mission().getType() == MissionType.MULTIPLE_CHOICE) {
			List<MissionChoiceView> choices = missionChoiceRepository
					.findByMissionIdOrderBySortOrderAsc(common.mission().getId()).stream().map(this::toChoiceView)
					.toList();
			return toMultipleChoiceDetailView(tripId, common, choices);
		}
		return toDetailView(tripId, common);
	}

	private MissionItemView toView(UUID tripId, NearbyMissionProjection row) {
		MissionCommon common = computeCommon(row);
		MissionEntity mission = common.mission();
		PlaceEntity place = common.place();
		return new MissionItemView(mission.getId(), tripId, place.getId(), place.getName(),
				mission.getLocation().getY(), mission.getLocation().getX(), common.distanceMeters(), mission.getType(),
				mission.getTitle(), mission.getRewardPoints(), common.availability(), PARTICIPATION_RADIUS_METERS,
				common.remainingAttempts());
	}

	private MissionRestrictedView toRestrictedView(UUID tripId, MissionCommon common) {
		MissionEntity mission = common.mission();
		PlaceEntity place = common.place();
		return new MissionRestrictedView(mission.getId(), tripId, place.getId(), place.getName(),
				mission.getLocation().getY(), mission.getLocation().getX(), common.distanceMeters(), mission.getType(),
				mission.getTitle(), mission.getRewardPoints(), common.availability(), PARTICIPATION_RADIUS_METERS,
				common.remainingAttempts());
	}

	private MissionDetailView toDetailView(UUID tripId, MissionCommon common) {
		MissionEntity mission = common.mission();
		PlaceEntity place = common.place();
		return new MissionDetailView(mission.getId(), tripId, place.getId(), place.getName(),
				mission.getLocation().getY(), mission.getLocation().getX(), common.distanceMeters(), mission.getType(),
				mission.getTitle(), mission.getRewardPoints(), common.availability(), PARTICIPATION_RADIUS_METERS,
				common.remainingAttempts(), mission.getDescription());
	}

	private MissionMultipleChoiceDetailView toMultipleChoiceDetailView(UUID tripId, MissionCommon common,
			List<MissionChoiceView> choices) {
		MissionEntity mission = common.mission();
		PlaceEntity place = common.place();
		return new MissionMultipleChoiceDetailView(mission.getId(), tripId, place.getId(), place.getName(),
				mission.getLocation().getY(), mission.getLocation().getX(), common.distanceMeters(), mission.getType(),
				mission.getTitle(), mission.getRewardPoints(), common.availability(), PARTICIPATION_RADIUS_METERS,
				common.remainingAttempts(), mission.getDescription(), choices);
	}

	private MissionChoiceView toChoiceView(MissionChoiceEntity choice) {
		return new MissionChoiceView(choice.getId(), choice.getLabel());
	}

	private MissionCommon computeCommon(NearbyMissionProjection row) {
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

		return new MissionCommon(mission, place, distanceMeters, availability, remainingAttempts,
				withinParticipationRadius);
	}

	private record MissionCommon(MissionEntity mission, PlaceEntity place, int distanceMeters,
			MissionAvailability availability, Integer remainingAttempts, boolean withinParticipationRadius) {
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

	public record MissionRestrictedView(UUID missionId, UUID tripId, UUID placeId, String placeName, double latitude,
			double longitude, int distanceMeters, MissionType type, String title, int rewardPoints,
			MissionAvailability availability, int participationRadiusMeters, Integer remainingAttempts) {
	}

	public record MissionDetailView(UUID missionId, UUID tripId, UUID placeId, String placeName, double latitude,
			double longitude, int distanceMeters, MissionType type, String title, int rewardPoints,
			MissionAvailability availability, int participationRadiusMeters, Integer remainingAttempts,
			String description) {
	}

	public record MissionMultipleChoiceDetailView(UUID missionId, UUID tripId, UUID placeId, String placeName,
			double latitude, double longitude, int distanceMeters, MissionType type, String title, int rewardPoints,
			MissionAvailability availability, int participationRadiusMeters, Integer remainingAttempts,
			String description, List<MissionChoiceView> choices) {
		public MissionMultipleChoiceDetailView {
			choices = List.copyOf(choices);
		}
	}

	public record MissionChoiceView(UUID choiceId, String label) {
	}
}
