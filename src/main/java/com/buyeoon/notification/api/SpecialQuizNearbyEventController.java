package com.buyeoon.notification.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.notification.application.SpecialQuizNearbyEventService;
import com.buyeoon.notification.application.SpecialQuizNearbyEventService.LocationCommand;
import com.buyeoon.notification.application.SpecialQuizNearbyEventService.SpecialQuizNearbyEventCommand;
import com.buyeoon.notification.application.SpecialQuizNearbyEventService.SpecialQuizNearbyEventView;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class SpecialQuizNearbyEventController {

	private final SpecialQuizNearbyEventService specialQuizNearbyEventService;

	public SpecialQuizNearbyEventController(SpecialQuizNearbyEventService specialQuizNearbyEventService) {
		this.specialQuizNearbyEventService = specialQuizNearbyEventService;
	}

	@PostMapping("/notifications/missions/{missionId}/nearby-events")
	public SuccessResponse<SpecialQuizNearbyEventView> notify(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID missionId,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(
				specialQuizNearbyEventService.notify(memberId, missionId, idempotencyKey, parseRequest(request)));
	}

	private SpecialQuizNearbyEventCommand parseRequest(JsonNode request) {
		if (request == null || !request.isObject() || !hasOnlyProperties(request, Set.of("tripId", "location"))) {
			throw new InvalidNotificationRequestException();
		}
		return new SpecialQuizNearbyEventCommand(tripId(request.get("tripId")), location(request.get("location")));
	}

	private UUID tripId(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidNotificationRequestException();
		}
		try {
			return UUID.fromString(node.stringValue());
		} catch (IllegalArgumentException exception) {
			throw new InvalidNotificationRequestException();
		}
	}

	private LocationCommand location(JsonNode node) {
		Set<String> fields = Set.of("latitude", "longitude", "accuracyMeters", "capturedAt");
		if (node == null || !node.isObject() || !hasOnlyOptionalProperties(node, fields, 3, 4)) {
			throw new InvalidNotificationRequestException();
		}
		double latitude = finiteNumber(node.get("latitude"));
		double longitude = finiteNumber(node.get("longitude"));
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw new InvalidNotificationRequestException();
		}
		Double accuracy = nullableAccuracy(node.get("accuracyMeters"));
		OffsetDateTime capturedAt = dateTime(node.get("capturedAt"));
		return new LocationCommand(latitude, longitude, accuracy, capturedAt);
	}

	private double finiteNumber(JsonNode node) {
		if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) {
			throw new InvalidNotificationRequestException();
		}
		return node.doubleValue();
	}

	private Double nullableAccuracy(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		double accuracy = finiteNumber(node);
		if (accuracy < 0) {
			throw new InvalidNotificationRequestException();
		}
		return accuracy;
	}

	private OffsetDateTime dateTime(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidNotificationRequestException();
		}
		try {
			return OffsetDateTime.parse(node.stringValue());
		} catch (DateTimeParseException exception) {
			throw new InvalidNotificationRequestException();
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
