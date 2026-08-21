package com.buyeoon.trip;

import com.buyeoon.badge.entity.BadgeEntity;
import com.buyeoon.common.storage.PrivateImageGetUrlService;
import com.buyeoon.common.storage.PublicImageUrlService;
import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.member.application.ResourceNotFoundException;
import com.buyeoon.mission.entity.MissionPhotoEntity;
import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.point.application.PointSummaryService;
import com.buyeoon.point.application.PointSummaryService.PointSummaryView;
import com.buyeoon.trip.TripQueryService.TripStatisticsView;
import com.buyeoon.trip.entity.TripEntity;
import com.buyeoon.trip.entity.TripStatus;
import com.buyeoon.trip.entity.VisitRecordEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * UC-25 발자취 조회 서비스다. 여러 도메인의 공개 seam을 조합해 하나의 응답으로 반환한다. {@code points}가 호출하는
 * {@code PointExpirationService}는 만료 확정을 위해 활성 회원 행에 쓰기 트랜잭션을 열어야 하므로, 이 서비스는
 * {@code @Transactional(readOnly = true)}로 감싸지 않고 각 하위 조회가 자체 트랜잭션 경계를 갖도록 둔다.
 * SETTLED는 종단 상태라 trip·visits·badges·photos는 조회 중 값이 바뀌지 않으며, points는 여행이 아닌 회원
 * 전체 잔액이라 나머지 조각과 원자적으로 일치할 필요가 없다.
 */
@Service
public class FootprintQueryService {

	private final TripRepository tripRepository;
	private final TripQueryService tripQueryService;
	private final VisitRecordRepository visitRepository;
	private final FootprintPlaceRepository placeRepository;
	private final FootprintSavedPlaceRepository savedPlaceRepository;
	private final PointSummaryService pointSummaryService;
	private final FootprintBadgeRepository badgeRepository;
	private final FootprintPhotoRepository photoRepository;
	private final PublicImageUrlService publicImageUrls;
	private final PrivateImageGetUrlService privateImageUrls;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public FootprintQueryService(TripRepository tripRepository, TripQueryService tripQueryService,
			VisitRecordRepository visitRepository, FootprintPlaceRepository placeRepository,
			FootprintSavedPlaceRepository savedPlaceRepository, PointSummaryService pointSummaryService,
			FootprintBadgeRepository badgeRepository, FootprintPhotoRepository photoRepository,
			PublicImageUrlService publicImageUrls, PrivateImageGetUrlService privateImageUrls) {
		this.tripRepository = tripRepository;
		this.tripQueryService = tripQueryService;
		this.visitRepository = visitRepository;
		this.placeRepository = placeRepository;
		this.savedPlaceRepository = savedPlaceRepository;
		this.pointSummaryService = pointSummaryService;
		this.badgeRepository = badgeRepository;
		this.photoRepository = photoRepository;
		this.publicImageUrls = publicImageUrls;
		this.privateImageUrls = privateImageUrls;
	}

	/**
	 * 정산 완료(SETTLED)된 본인 소유 여행의 발자취를 조회한다. 존재하지 않거나 타 회원 소유면 404, 정산 완료 전이면 409다.
	 */
	public FootprintView getFootprint(UUID memberId, UUID tripId) {
		TripEntity trip = tripRepository.findByIdAndMemberId(tripId, memberId)
				.orElseThrow(ResourceNotFoundException::new);
		if (trip.getStatus() != TripStatus.SETTLED) {
			throw new InvalidStateTransitionException();
		}

		FootprintTripView tripView = FootprintTripView.from(trip);
		TripStatisticsView statistics = tripQueryService.getStatistics(memberId, tripId);
		List<VisitView> visits = visits(memberId, tripId);
		PointSummaryView points = pointSummaryService.getSummary(memberId);
		List<BadgeView> badges = badges(memberId, tripId);
		List<PhotoView> photos = photos(memberId, tripId);
		return new FootprintView(tripView, statistics, visits, points, badges, photos);
	}

	private List<VisitView> visits(UUID memberId, UUID tripId) {
		List<VisitRecordEntity> visitRecords = visitRepository.findByTripIdOrderByVisitedAtAsc(tripId);
		if (visitRecords.isEmpty()) {
			return List.of();
		}
		List<UUID> placeIds = visitRecords.stream().map(VisitRecordEntity::getPlaceId).toList();
		Map<UUID, PlaceEntity> places = placeRepository.findAllById(placeIds).stream()
				.collect(Collectors.toMap(PlaceEntity::getId, Function.identity()));
		Set<UUID> savedPlaceIds = Set.copyOf(savedPlaceRepository.findSavedPlaceIds(memberId, placeIds));
		return visitRecords.stream().map(visitRecord -> toVisitView(visitRecord, places, savedPlaceIds)).toList();
	}

	private VisitView toVisitView(VisitRecordEntity visitRecord, Map<UUID, PlaceEntity> places,
			Set<UUID> savedPlaceIds) {
		PlaceEntity place = places.get(visitRecord.getPlaceId());
		if (place == null) {
			throw new IllegalStateException("방문 기록이 참조하는 장소를 찾을 수 없습니다. placeId=" + visitRecord.getPlaceId());
		}
		boolean saved = savedPlaceIds.contains(place.getId());
		return new VisitView(visitRecord.getId(), visitRecord.getMissionId(), toPlaceView(place, saved),
				visitRecord.getVisitedAt());
	}

	private PlaceView toPlaceView(PlaceEntity place, boolean saved) {
		String imageUrl = place.getImageKey() != null
				? publicImageUrls.create(place.getImageKey())
				: place.getSourceImageHref();
		return new PlaceView(place.getId(), place.getCategory(), place.getName(), place.getSummary(),
				place.getDescription(), place.getAddress(), imageUrl, place.getLocation().getY(),
				place.getLocation().getX(), saved);
	}

	private List<BadgeView> badges(UUID memberId, UUID tripId) {
		return badgeRepository.findBadgesByMemberIdAndTripId(memberId, tripId).stream().map(this::toBadgeView).toList();
	}

	private BadgeView toBadgeView(EarnedBadgeProjection projection) {
		BadgeEntity badge = projection.badge();
		String imageUrl = badge.getImageKey() != null ? publicImageUrls.create(badge.getImageKey()) : null;
		return new BadgeView(badge.getId(), badge.getName(), imageUrl, badge.getConditionText(), projection.earnedAt());
	}

	private List<PhotoView> photos(UUID memberId, UUID tripId) {
		return photoRepository.findByMemberIdAndTripIdOrderByUploadedAtAsc(memberId, tripId).stream()
				.map(this::toPhotoView).toList();
	}

	private PhotoView toPhotoView(MissionPhotoEntity photo) {
		return new PhotoView(photo.getId(), privateImageUrls.create(photo.getObjectKey()), photo.getUploadedAt());
	}

	public record FootprintView(FootprintTripView trip, TripStatisticsView statistics, List<VisitView> visits,
			PointSummaryView points, List<BadgeView> badges, List<PhotoView> photos) {
		public FootprintView {
			visits = List.copyOf(visits);
			badges = List.copyOf(badges);
			photos = List.copyOf(photos);
		}
	}

	public record FootprintTripView(UUID tripId, TripStatus status, Instant startedAt, Instant endedAt,
			Instant settledAt) {
		static FootprintTripView from(TripEntity trip) {
			return new FootprintTripView(trip.getId(), trip.getStatus(), trip.getStartedAt(), trip.getEndedAt(),
					trip.getSettledAt());
		}
	}

	public record VisitView(UUID visitId, UUID missionId, PlaceView place, Instant visitedAt) {
	}

	public record PlaceView(UUID placeId, PlaceCategory category, String name, String summary, String description,
			String address, String imageUrl, double latitude, double longitude, boolean saved) {
	}

	public record BadgeView(UUID badgeId, String name, String imageUrl, String condition, Instant earnedAt) {
	}

	public record PhotoView(UUID photoId, String url, Instant uploadedAt) {
	}
}
