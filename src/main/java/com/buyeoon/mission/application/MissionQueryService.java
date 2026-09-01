package com.buyeoon.mission.application;

import com.buyeoon.common.location.ParticipationRadiusPolicy;
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

/** UC-06 주변 미션 목록과 상세 조회 서비스다. */
@Service
@Transactional(readOnly = true)
public class MissionQueryService {

	private final MissionQueryRepository missionQueryRepository;
	private final MissionChoiceRepository missionChoiceRepository;
	private final TripQueryService tripQueryService;
	private final SpecialQuizExposureDecider specialQuizExposureDecider;

	public MissionQueryService(MissionQueryRepository missionQueryRepository,
			MissionChoiceRepository missionChoiceRepository, TripQueryService tripQueryService,
			SpecialQuizExposureDecider specialQuizExposureDecider) {
		this.missionQueryRepository = missionQueryRepository;
		this.missionChoiceRepository = missionChoiceRepository;
		this.tripQueryService = tripQueryService;
		this.specialQuizExposureDecider = specialQuizExposureDecider;
	}

	public MissionListView listNearby(UUID memberId, UUID tripId, double latitude, double longitude) {
		TripStatus status = tripQueryService.findOwnedTripStatus(memberId, tripId)
				.orElseThrow(TripNotFoundException::new);
		if (status != TripStatus.IN_PROGRESS) {
			throw new TripNotInProgressException();
		}

		List<MissionItemView> items = missionQueryRepository.findNearby(tripId, latitude, longitude).stream()
				.filter(row -> isExposedToday(tripId, row)).map(row -> toView(tripId, row)).toList();
		return new MissionListView(items);
	}

	public Object getMission(UUID memberId, UUID missionId, UUID tripId, double latitude, double longitude) {
		TripStatus status = tripQueryService.findOwnedTripStatus(memberId, tripId)
				.orElseThrow(TripNotFoundException::new);
		if (status != TripStatus.IN_PROGRESS) {
			throw new TripNotInProgressException();
		}

		NearbyMissionProjection row = missionQueryRepository.findDetail(missionId, tripId, latitude, longitude)
				.filter(candidate -> isExposedToday(tripId, candidate)).orElseThrow(MissionNotFoundException::new);
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

	/**
	 * notification 도메인이 스페셜 퀴즈 근접 알림을 검증할 때 사용하는 공개 seam이다. 여행 소유·진행 중 여부를 먼저 확인한
	 * 뒤, 오늘 이 회원에게 노출된 스페셜 퀴즈인지·이미 참여했는지·참여 반경 이내인지를 함께 판정한다.
	 */
	public SpecialQuizNearbyCheck checkSpecialQuizNearby(UUID memberId, UUID tripId, UUID missionId, double latitude,
			double longitude) {
		TripStatus status = tripQueryService.findOwnedTripStatus(memberId, tripId)
				.orElseThrow(TripNotFoundException::new);
		if (status != TripStatus.IN_PROGRESS) {
			throw new TripNotInProgressException();
		}

		NearbyMissionProjection row = missionQueryRepository.findDetail(missionId, tripId, latitude, longitude)
				.orElseThrow(MissionNotFoundException::new);
		MissionEntity mission = row.mission();
		boolean specialQuiz = mission.getMaxAttempts() != null;
		boolean exposedToday = specialQuiz && specialQuizExposureDecider.isExposedToday(tripId, missionId);

		MissionParticipationEntity participation = row.participation();
		MissionStatus persistedStatus = participation == null ? MissionStatus.AVAILABLE : participation.getStatus();
		boolean alreadyParticipated = persistedStatus != MissionStatus.AVAILABLE;

		boolean withinParticipationRadius = row
				.distanceMeters() <= ParticipationRadiusPolicy.PARTICIPATION_RADIUS_METERS;

		return new SpecialQuizNearbyCheck(specialQuiz, exposedToday, alreadyParticipated, withinParticipationRadius);
	}

	private MissionItemView toView(UUID tripId, NearbyMissionProjection row) {
		MissionCommon common = computeCommon(row);
		MissionEntity mission = common.mission();
		PlaceEntity place = common.place();
		return new MissionItemView(mission.getId(), tripId, place.getId(), place.getName(),
				mission.getLocation().getY(), mission.getLocation().getX(), common.distanceMeters(), mission.getType(),
				mission.getTitle(), mission.getRewardPoints(), common.availability(),
				ParticipationRadiusPolicy.PARTICIPATION_RADIUS_METERS, common.remainingAttempts());
	}

	private MissionRestrictedView toRestrictedView(UUID tripId, MissionCommon common) {
		MissionEntity mission = common.mission();
		PlaceEntity place = common.place();
		return new MissionRestrictedView(mission.getId(), tripId, place.getId(), place.getName(),
				mission.getLocation().getY(), mission.getLocation().getX(), common.distanceMeters(), mission.getType(),
				mission.getTitle(), mission.getRewardPoints(), common.availability(),
				ParticipationRadiusPolicy.PARTICIPATION_RADIUS_METERS, common.remainingAttempts());
	}

	private MissionDetailView toDetailView(UUID tripId, MissionCommon common) {
		MissionEntity mission = common.mission();
		PlaceEntity place = common.place();
		return new MissionDetailView(mission.getId(), tripId, place.getId(), place.getName(),
				mission.getLocation().getY(), mission.getLocation().getX(), common.distanceMeters(), mission.getType(),
				mission.getTitle(), mission.getRewardPoints(), common.availability(),
				ParticipationRadiusPolicy.PARTICIPATION_RADIUS_METERS, common.remainingAttempts(),
				mission.getDescription());
	}

	private MissionMultipleChoiceDetailView toMultipleChoiceDetailView(UUID tripId, MissionCommon common,
			List<MissionChoiceView> choices) {
		MissionEntity mission = common.mission();
		PlaceEntity place = common.place();
		return new MissionMultipleChoiceDetailView(mission.getId(), tripId, place.getId(), place.getName(),
				mission.getLocation().getY(), mission.getLocation().getX(), common.distanceMeters(), mission.getType(),
				mission.getTitle(), mission.getRewardPoints(), common.availability(),
				ParticipationRadiusPolicy.PARTICIPATION_RADIUS_METERS, common.remainingAttempts(),
				mission.getDescription(), choices);
	}

	private MissionChoiceView toChoiceView(MissionChoiceEntity choice) {
		return new MissionChoiceView(choice.getId(), choice.getLabel());
	}

	/**
	 * 스페셜 퀴즈(최대 도전 횟수가 있는 객관식·OX 미션)는 여행·KST 날짜·미션 ID로 정해지는 시드로 하루 20%만 노출한다. 완료·기회
	 * 소진 상태는 이미 참여한 기록이라 노출 대상에서 빼지 않고 항상 보여준다.
	 */
	private boolean isExposedToday(UUID tripId, NearbyMissionProjection row) {
		MissionEntity mission = row.mission();
		if (mission.getMaxAttempts() == null) {
			return true;
		}
		MissionParticipationEntity participation = row.participation();
		MissionStatus persistedStatus = participation == null ? MissionStatus.AVAILABLE : participation.getStatus();
		if (persistedStatus != MissionStatus.AVAILABLE) {
			return true;
		}
		return specialQuizExposureDecider.isExposedToday(tripId, mission.getId());
	}

	private MissionCommon computeCommon(NearbyMissionProjection row) {
		MissionEntity mission = row.mission();
		PlaceEntity place = row.place();
		MissionParticipationEntity participation = row.participation();

		MissionStatus persistedStatus = participation == null ? MissionStatus.AVAILABLE : participation.getStatus();
		int attemptCount = participation == null ? 0 : participation.getAttemptCount();
		boolean withinParticipationRadius = row.distanceMeters() <= ParticipationRadiusPolicy.PARTICIPATION_RADIUS_METERS;

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

	/** {@link #checkSpecialQuizNearby}의 검증 결과다. */
	public record SpecialQuizNearbyCheck(boolean specialQuiz, boolean exposedToday, boolean alreadyParticipated,
			boolean withinParticipationRadius) {
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
