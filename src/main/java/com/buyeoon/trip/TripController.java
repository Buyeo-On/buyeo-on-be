package com.buyeoon.trip;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.point.application.PointSummaryService.PointSummaryView;
import com.buyeoon.trip.FootprintQueryService.BadgeView;
import com.buyeoon.trip.FootprintQueryService.FootprintView;
import com.buyeoon.trip.FootprintQueryService.PhotoView;
import com.buyeoon.trip.FootprintQueryService.VisitView;
import com.buyeoon.trip.TripQueryService.TripStatisticsView;
import com.buyeoon.trip.TripStartService.LocationCommand;
import com.buyeoon.trip.TripStartService.TripStartCommand;
import com.buyeoon.trip.TripStartService.TripView;
import com.buyeoon.trip.entity.TripStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class TripController {

	private final TripStarter tripStart;
	private final TripEnder tripEnd;
	private final TripQueryService tripQuery;
	private final FootprintQueryService footprintQuery;

	public TripController(TripStarter tripStart, TripEnder tripEnd, TripQueryService tripQuery,
			FootprintQueryService footprintQuery) {
		this.tripStart = tripStart;
		this.tripEnd = tripEnd;
		this.tripQuery = tripQuery;
		this.footprintQuery = footprintQuery;
	}

	@PostMapping("/trips")
	public ResponseEntity<SuccessResponse<TripView>> start(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		TripView trip = tripStart.start(memberId, idempotencyKey, parseRequest(request));
		return ResponseEntity.status(HttpStatus.CREATED).body(SuccessResponse.of(trip));
	}

	@GetMapping("/trips/current")
	public ResponseEntity<SuccessResponse<TripView>> current(@AuthenticationPrincipal Jwt jwt) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		TripView trip = tripQuery.getCurrentTrip(memberId);
		return ResponseEntity.ok(SuccessResponse.of(trip));
	}

	@PostMapping("/trips/{tripId}/end")
	public ResponseEntity<SuccessResponse<TripView>> end(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tripId,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		TripView trip = tripEnd.end(memberId, tripId, idempotencyKey);
		return ResponseEntity.ok(SuccessResponse.of(trip));
	}

	@GetMapping("/trips/{tripId}/statistics")
	public ResponseEntity<SuccessResponse<TripStatisticsResponse>> statistics(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID tripId) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		TripStatisticsView statistics = tripQuery.getStatistics(memberId, tripId);
		return ResponseEntity.ok(SuccessResponse.of(TripStatisticsResponse.from(statistics)));
	}

	@GetMapping("/trips/{tripId}/footprint")
	public ResponseEntity<SuccessResponse<FootprintResponse>> footprint(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID tripId) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		FootprintView footprint = footprintQuery.getFootprint(memberId, tripId);
		return ResponseEntity.ok(SuccessResponse.of(FootprintResponse.from(footprint)));
	}

	@GetMapping("/trips/{tripId}/photos")
	public ResponseEntity<SuccessResponse<TripPhotoListResponse>> photos(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID tripId) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		TripQueryService.PhotoListView photos = tripQuery.getPhotos(memberId, tripId);
		return ResponseEntity.ok(SuccessResponse.of(TripPhotoListResponse.from(photos)));
	}

	/** 이동 거리·소모 칼로리는 MVP에서 계산하지 않으므로 항상 null이다. */
	private record TripStatisticsResponse(UUID tripId, Double distanceKm, long visitedPlaceCount, long durationMinutes,
			Integer caloriesKcal) {
		static TripStatisticsResponse from(TripStatisticsView statistics) {
			return new TripStatisticsResponse(statistics.tripId(), null, statistics.visitedPlaceCount(),
					statistics.durationMinutes(), null);
		}
	}

	private record FootprintResponse(FootprintTripResponse trip, TripStatisticsResponse statistics,
			List<FootprintVisitResponse> visits, PointSummaryView points, List<FootprintBadgeResponse> badges,
			List<FootprintPhotoResponse> photos) {
		static FootprintResponse from(FootprintView footprint) {
			TripStatisticsResponse statistics = new TripStatisticsResponse(footprint.statistics().tripId(), null,
					footprint.statistics().visitedPlaceCount(), footprint.statistics().durationMinutes(), null);
			List<FootprintVisitResponse> visits = footprint.visits().stream().map(FootprintVisitResponse::from)
					.toList();
			List<FootprintBadgeResponse> badges = footprint.badges().stream().map(FootprintBadgeResponse::from)
					.toList();
			List<FootprintPhotoResponse> photos = footprint.photos().stream().map(FootprintPhotoResponse::from)
					.toList();
			return new FootprintResponse(FootprintTripResponse.from(footprint.trip()), statistics, visits,
					footprint.points(), badges, photos);
		}
	}

	private record FootprintTripResponse(UUID tripId, TripStatus status, Instant startedAt, Instant endedAt,
			Instant settledAt) {
		static FootprintTripResponse from(FootprintQueryService.FootprintTripView trip) {
			return new FootprintTripResponse(trip.tripId(), trip.status(), trip.startedAt(), trip.endedAt(),
					trip.settledAt());
		}
	}

	private record FootprintPlaceResponse(UUID placeId, PlaceCategory category, String name, String summary,
			String description, String address, String imageUrl, double latitude, double longitude,
			Integer distanceMeters, Integer walkingMinutes, boolean saved) {
		static FootprintPlaceResponse from(FootprintQueryService.PlaceView place) {
			return new FootprintPlaceResponse(place.placeId(), place.category(), place.name(), place.summary(),
					place.description(), place.address(), place.imageUrl(), place.latitude(), place.longitude(), null,
					null, place.saved());
		}
	}

	private record FootprintVisitResponse(UUID visitId, UUID missionId, FootprintPlaceResponse place,
			Instant visitedAt) {
		static FootprintVisitResponse from(VisitView visit) {
			return new FootprintVisitResponse(visit.visitId(), visit.missionId(),
					FootprintPlaceResponse.from(visit.place()), visit.visitedAt());
		}
	}

	private record FootprintBadgeResponse(UUID badgeId, String name, String imageUrl, String condition,
			Instant earnedAt) {
		static FootprintBadgeResponse from(BadgeView badge) {
			return new FootprintBadgeResponse(badge.badgeId(), badge.name(), badge.imageUrl(), badge.condition(),
					badge.earnedAt());
		}
	}

	private record FootprintPhotoResponse(UUID photoId, String url, Instant uploadedAt, String placeName) {
		static FootprintPhotoResponse from(PhotoView photo) {
			return new FootprintPhotoResponse(photo.photoId(), photo.url(), photo.uploadedAt(), photo.placeName());
		}
	}

	private record TripPhotoListResponse(List<TripPhotoResponse> items) {
		static TripPhotoListResponse from(TripQueryService.PhotoListView view) {
			return new TripPhotoListResponse(view.items().stream().map(TripPhotoResponse::from).toList());
		}
	}

	private record TripPhotoResponse(UUID photoId, String url, Instant uploadedAt, String placeName) {
		static TripPhotoResponse from(TripQueryService.PhotoView photo) {
			return new TripPhotoResponse(photo.photoId(), photo.url(), photo.uploadedAt(), photo.placeName());
		}
	}

	private TripStartCommand parseRequest(JsonNode request) {
		if (request == null || !request.isObject() || !hasOnlyProperties(request, Set.of("location"))) {
			throw new InvalidTripStartRequestException();
		}
		return new TripStartCommand(location(request.get("location")));
	}

	private LocationCommand location(JsonNode node) {
		Set<String> fields = Set.of("latitude", "longitude", "accuracyMeters", "capturedAt");
		if (node == null || !node.isObject() || !hasOnlyOptionalProperties(node, fields, 3, 4)) {
			throw new InvalidTripStartRequestException();
		}
		double latitude = finiteNumber(node.get("latitude"));
		double longitude = finiteNumber(node.get("longitude"));
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw new InvalidTripStartRequestException();
		}
		Double accuracy = nullableAccuracy(node.get("accuracyMeters"));
		OffsetDateTime capturedAt = dateTime(node.get("capturedAt"));
		return new LocationCommand(latitude, longitude, accuracy, capturedAt);
	}

	private double finiteNumber(JsonNode node) {
		if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) {
			throw new InvalidTripStartRequestException();
		}
		return node.doubleValue();
	}

	private Double nullableAccuracy(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		double accuracy = finiteNumber(node);
		if (accuracy < 0) {
			throw new InvalidTripStartRequestException();
		}
		return accuracy;
	}

	private OffsetDateTime dateTime(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidTripStartRequestException();
		}
		try {
			return OffsetDateTime.parse(node.stringValue());
		} catch (DateTimeParseException exception) {
			throw new InvalidTripStartRequestException();
		}
	}

	private boolean hasOnlyProperties(JsonNode node, Set<String> properties) {
		return node.size() == properties.size()
				&& node.properties().stream().allMatch(property -> properties.contains(property.getKey()));
	}

	private boolean hasOnlyOptionalProperties(JsonNode node, Set<String> properties, int minimum, int maximum) {
		return node.size() >= minimum && node.size() <= maximum
				&& node.properties().stream().allMatch(property -> properties.contains(property.getKey()))
				&& node.has("latitude") && node.has("longitude") && node.has("capturedAt");
	}
}
