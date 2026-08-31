package com.buyeoon.notification.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.notification.application.BuyeoEntryEventService;
import com.buyeoon.notification.application.BuyeoEntryEventService.BuyeoEntryEventCommand;
import com.buyeoon.notification.application.BuyeoEntryEventService.BuyeoEntryEventView;
import com.buyeoon.notification.application.BuyeoEntryEventService.LocationCommand;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class BuyeoEntryEventController {

	private final BuyeoEntryEventService buyeoEntryEventService;

	public BuyeoEntryEventController(BuyeoEntryEventService buyeoEntryEventService) {
		this.buyeoEntryEventService = buyeoEntryEventService;
	}

	@PostMapping("/notifications/buyeo-entry-events")
	public SuccessResponse<BuyeoEntryEventView> notify(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(buyeoEntryEventService.notify(memberId, idempotencyKey, parseRequest(request)));
	}

	private BuyeoEntryEventCommand parseRequest(JsonNode request) {
		if (request == null || !request.isObject() || !hasOnlyProperties(request, Set.of("location"))) {
			throw new InvalidNotificationRequestException();
		}
		return new BuyeoEntryEventCommand(location(request.get("location")));
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
