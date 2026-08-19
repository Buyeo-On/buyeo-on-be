package com.buyeoon.mission.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.mission.application.MissionPhotoUploadUrlService;
import com.buyeoon.mission.application.MissionPhotoUploadUrlService.MissionPhotoUploadUrlCommand;
import com.buyeoon.mission.application.MissionPhotoUploadUrlService.MissionPhotoUploadUrlView;
import com.buyeoon.mission.application.MissionQueryService;
import com.buyeoon.mission.application.MissionQueryService.MissionListView;
import com.buyeoon.mission.application.MissionSubmissionService;
import com.buyeoon.mission.application.MissionSubmissionService.LocationCommand;
import com.buyeoon.mission.application.MissionSubmissionService.MissionSubmissionCommand;
import com.buyeoon.mission.application.MissionSubmissionService.MissionSubmissionView;
import com.buyeoon.mission.entity.MissionType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class MissionController {

	private static final Set<String> ALLOWED_PHOTO_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

	private final MissionQueryService missionQueryService;
	private final MissionSubmissionService missionSubmissionService;
	private final MissionPhotoUploadUrlService missionPhotoUploadUrlService;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public MissionController(MissionQueryService missionQueryService, MissionSubmissionService missionSubmissionService,
			MissionPhotoUploadUrlService missionPhotoUploadUrlService) {
		this.missionQueryService = missionQueryService;
		this.missionSubmissionService = missionSubmissionService;
		this.missionPhotoUploadUrlService = missionPhotoUploadUrlService;
	}

	@GetMapping("/missions/nearby")
	public SuccessResponse<MissionListView> getNearbyMissions(@AuthenticationPrincipal Jwt jwt,
			@RequestParam String latitude, @RequestParam String longitude, @RequestParam UUID tripId) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse
				.of(missionQueryService.listNearby(memberId, tripId, latitude(latitude), longitude(longitude)));
	}

	@GetMapping("/missions/{missionId}")
	public SuccessResponse<Object> getMission(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID missionId,
			@RequestParam String latitude, @RequestParam String longitude, @RequestParam UUID tripId) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(
				missionQueryService.getMission(memberId, missionId, tripId, latitude(latitude), longitude(longitude)));
	}

	@PostMapping("/missions/{missionId}/submissions")
	public SuccessResponse<MissionSubmissionView> submitMission(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID missionId,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		MissionSubmissionCommand command = parseSubmission(request);
		return SuccessResponse.of(missionSubmissionService.submit(memberId, missionId, idempotencyKey, command));
	}

	@PostMapping("/mission-photos/presigned-url")
	public ResponseEntity<SuccessResponse<MissionPhotoUploadUrlView>> createMissionPhotoUploadUrl(
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		MissionPhotoUploadUrlCommand command = parsePhotoUploadUrlRequest(request);
		MissionPhotoUploadUrlView view = missionPhotoUploadUrlService.createUploadUrl(memberId, idempotencyKey,
				command);
		return ResponseEntity.status(HttpStatus.CREATED).body(SuccessResponse.of(view));
	}

	private MissionPhotoUploadUrlCommand parsePhotoUploadUrlRequest(JsonNode request) {
		Set<String> fields = Set.of("tripId", "missionId", "fileName", "contentType", "fileSizeBytes");
		if (request == null || !request.isObject() || !hasOnlyProperties(request, fields)) {
			throw new InvalidMissionRequestException();
		}
		UUID tripId = uuid(request.get("tripId"));
		UUID missionId = uuid(request.get("missionId"));
		String fileName = fileName(request.get("fileName"));
		String contentType = photoContentType(request.get("contentType"));
		long fileSizeBytes = fileSizeBytes(request.get("fileSizeBytes"));
		return new MissionPhotoUploadUrlCommand(tripId, missionId, fileName, contentType, fileSizeBytes);
	}

	private String fileName(JsonNode node) {
		String value = text(node);
		if (value.isEmpty() || value.length() > 255) {
			throw new InvalidMissionRequestException();
		}
		return value;
	}

	private String photoContentType(JsonNode node) {
		String value = text(node);
		if (!ALLOWED_PHOTO_CONTENT_TYPES.contains(value)) {
			throw new InvalidMissionRequestException();
		}
		return value;
	}

	private long fileSizeBytes(JsonNode node) {
		if (node == null || !node.isNumber()) {
			throw new InvalidMissionRequestException();
		}
		long value = node.longValue();
		if (value < 1) {
			throw new InvalidMissionRequestException();
		}
		return value;
	}

	private MissionSubmissionCommand parseSubmission(JsonNode request) {
		if (request == null || !request.isObject()) {
			throw new InvalidMissionRequestException();
		}
		MissionType type = missionType(request.get("type"));
		Set<String> fields = switch (type) {
			case MULTIPLE_CHOICE -> Set.of("tripId", "type", "choiceId", "location");
			case OX -> Set.of("tripId", "type", "oxAnswer", "location");
			case PHOTO -> Set.of("tripId", "type", "photoId", "location");
		};
		if (!hasOnlyProperties(request, fields)) {
			throw new InvalidMissionRequestException();
		}
		UUID tripId = uuid(request.get("tripId"));
		String choiceId = type == MissionType.MULTIPLE_CHOICE ? text(request.get("choiceId")) : null;
		Boolean oxAnswer = type == MissionType.OX ? bool(request.get("oxAnswer")) : null;
		UUID photoId = type == MissionType.PHOTO ? uuid(request.get("photoId")) : null;
		LocationCommand location = location(request.get("location"));
		return new MissionSubmissionCommand(tripId, type, choiceId, oxAnswer, photoId, location);
	}

	private MissionType missionType(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidMissionRequestException();
		}
		return switch (node.stringValue()) {
			case "MULTIPLE_CHOICE" -> MissionType.MULTIPLE_CHOICE;
			case "OX" -> MissionType.OX;
			case "PHOTO" -> MissionType.PHOTO;
			default -> throw new InvalidMissionRequestException();
		};
	}

	private LocationCommand location(JsonNode node) {
		Set<String> fields = Set.of("latitude", "longitude", "accuracyMeters", "capturedAt");
		if (node == null || !node.isObject() || node.size() < 3 || node.size() > 4
				|| !node.properties().stream().allMatch(property -> fields.contains(property.getKey()))
				|| !node.has("latitude") || !node.has("longitude") || !node.has("capturedAt")) {
			throw new InvalidMissionRequestException();
		}
		double nodeLatitude = validateLatitude(finiteNumber(node.get("latitude")));
		double nodeLongitude = validateLongitude(finiteNumber(node.get("longitude")));
		Double accuracy = nullableAccuracy(node.get("accuracyMeters"));
		OffsetDateTime capturedAt = dateTime(node.get("capturedAt"));
		return new LocationCommand(nodeLatitude, nodeLongitude, accuracy, capturedAt);
	}

	private Double nullableAccuracy(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		double accuracy = finiteNumber(node);
		if (accuracy < 0) {
			throw new InvalidMissionRequestException();
		}
		return accuracy;
	}

	private double validateLatitude(double value) {
		if (value < -90 || value > 90) {
			throw new InvalidMissionRequestException();
		}
		return value;
	}

	private double validateLongitude(double value) {
		if (value < -180 || value > 180) {
			throw new InvalidMissionRequestException();
		}
		return value;
	}

	private double finiteNumber(JsonNode node) {
		if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) {
			throw new InvalidMissionRequestException();
		}
		return node.doubleValue();
	}

	private OffsetDateTime dateTime(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidMissionRequestException();
		}
		try {
			return OffsetDateTime.parse(node.stringValue());
		} catch (DateTimeParseException exception) {
			throw new InvalidMissionRequestException();
		}
	}

	private UUID uuid(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidMissionRequestException();
		}
		try {
			return UUID.fromString(node.stringValue());
		} catch (IllegalArgumentException exception) {
			throw new InvalidMissionRequestException();
		}
	}

	private String text(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidMissionRequestException();
		}
		return node.stringValue();
	}

	private Boolean bool(JsonNode node) {
		if (node == null || !node.isBoolean()) {
			throw new InvalidMissionRequestException();
		}
		return node.booleanValue();
	}

	private boolean hasOnlyProperties(JsonNode node, Set<String> properties) {
		return node.size() == properties.size()
				&& node.properties().stream().allMatch(property -> properties.contains(property.getKey()));
	}

	private double latitude(String value) {
		double latitude = coordinate(value);
		if (latitude < -90 || latitude > 90) {
			throw new InvalidMissionRequestException();
		}
		return latitude;
	}

	private double longitude(String value) {
		double longitude = coordinate(value);
		if (longitude < -180 || longitude > 180) {
			throw new InvalidMissionRequestException();
		}
		return longitude;
	}

	private double coordinate(String value) {
		try {
			double coordinate = Double.parseDouble(value);
			if (!Double.isFinite(coordinate)) {
				throw new InvalidMissionRequestException();
			}
			return coordinate;
		} catch (NumberFormatException exception) {
			throw new InvalidMissionRequestException();
		}
	}
}
