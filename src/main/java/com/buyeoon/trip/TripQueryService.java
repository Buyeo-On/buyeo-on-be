package com.buyeoon.trip;

import com.buyeoon.common.storage.PrivateImageGetUrlService;
import com.buyeoon.member.application.ResourceNotFoundException;
import com.buyeoon.trip.entity.TripEntity;
import com.buyeoon.trip.entity.TripStatus;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인이 여행 상태를 확인할 때 사용하는 trip 도메인의 공개 seam이다. */
@Service
@Transactional(readOnly = true)
public class TripQueryService {

	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");
	private static final Set<TripStatus> IN_PROGRESS_OR_UNSETTLED = Set.of(TripStatus.IN_PROGRESS, TripStatus.ENDED);

	private final TripRepository tripRepository;
	private final VisitRecordRepository visitRecordRepository;
	private final FootprintPhotoRepository photoRepository;
	private final PrivateImageGetUrlService privateImageUrls;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public TripQueryService(TripRepository tripRepository, VisitRecordRepository visitRecordRepository,
			FootprintPhotoRepository photoRepository, PrivateImageGetUrlService privateImageUrls) {
		this.tripRepository = tripRepository;
		this.visitRecordRepository = visitRecordRepository;
		this.photoRepository = photoRepository;
		this.privateImageUrls = privateImageUrls;
	}

	public boolean hasActiveTrip(UUID memberId) {
		return tripRepository.findByMemberIdAndStatus(memberId, TripStatus.IN_PROGRESS).isPresent();
	}

	/** 요청 회원이 진행 중이거나 종료 후 미정산인 여행을 가지고 있는지 확인한다. */
	public boolean hasInProgressOrUnsettledTrip(UUID memberId) {
		return tripRepository.existsByMemberIdAndStatusIn(memberId, IN_PROGRESS_OR_UNSETTLED);
	}

	/** 요청 회원이 소유한 여행의 현재 상태를 조회한다. 소유한 여행이 없으면 빈 값을 반환한다. */
	public Optional<TripStatus> findOwnedTripStatus(UUID memberId, UUID tripId) {
		return tripRepository.findByIdAndMemberId(tripId, memberId).map(TripEntity::getStatus);
	}

	/**
	 * 요청 회원의 진행 중이거나 종료 후 미정산인 여행을 조회한다. 그런 여행이 없으면 404 예외를 던진다. 프론트엔드가 미정산 여행의 정산
	 * 페이지로 라우팅할 수 있도록 종료된 여행도 함께 조회한다.
	 */
	public TripStartService.TripView getCurrentTrip(UUID memberId) {
		TripEntity trip = tripRepository
				.findByMemberIdAndStatusIn(memberId, List.of(TripStatus.IN_PROGRESS, TripStatus.ENDED))
				.orElseThrow(ResourceNotFoundException::new);
		ZonedDateTime endedAt = trip.getEndedAt() == null ? null : trip.getEndedAt().atZone(ASIA_SEOUL);
		return new TripStartService.TripView(trip.getId(), trip.getStatus(), trip.getStartedAt().atZone(ASIA_SEOUL),
				endedAt, null);
	}

	/** 요청 회원이 소유한 여행의 통계를 계산한다. 소유하지 않았거나 존재하지 않으면 404 예외를 던진다. */
	public TripStatisticsView getStatistics(UUID memberId, UUID tripId) {
		TripEntity trip = tripRepository.findByIdAndMemberId(tripId, memberId)
				.orElseThrow(ResourceNotFoundException::new);
		Instant until = trip.getStatus() == TripStatus.IN_PROGRESS ? Instant.now() : trip.getEndedAt();
		long durationMinutes = Duration.between(trip.getStartedAt(), until).toMinutes();
		long visitedPlaceCount = visitRecordRepository.countByTripId(tripId);
		return new TripStatisticsView(tripId, visitedPlaceCount, durationMinutes);
	}

	public record TripStatisticsView(UUID tripId, long visitedPlaceCount, long durationMinutes) {
	}

	/**
	 * 요청 회원이 소유한 여행에서 촬영한 사진을 업로드 시각 오름차순으로 조회한다. footprint와 달리 여행 상태와 무관하게 조회할 수
	 * 있다. 소유하지 않았거나 존재하지 않으면 404 예외를 던진다.
	 */
	public PhotoListView getPhotos(UUID memberId, UUID tripId) {
		tripRepository.findByIdAndMemberId(tripId, memberId).orElseThrow(ResourceNotFoundException::new);
		List<PhotoView> items = photoRepository.findWithPlaceNameByMemberIdAndTripId(memberId, tripId).stream()
				.map(projection -> new PhotoView(projection.photo().getId(),
						privateImageUrls.create(projection.photo().getObjectKey()), projection.photo().getUploadedAt(),
						projection.placeName()))
				.toList();
		return new PhotoListView(items);
	}

	public record PhotoListView(List<PhotoView> items) {
		public PhotoListView {
			items = List.copyOf(items);
		}
	}

	public record PhotoView(UUID photoId, String url, Instant uploadedAt, String placeName) {
	}
}
