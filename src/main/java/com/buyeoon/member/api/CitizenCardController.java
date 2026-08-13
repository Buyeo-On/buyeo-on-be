package com.buyeoon.member.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.member.application.CitizenCardCreationService.CitizenCardCommand;
import com.buyeoon.member.application.CitizenCardCreationService.CitizenCardView;
import com.buyeoon.member.application.CitizenCardCreationService.LocationCommand;
import com.buyeoon.member.application.CitizenCardCreator;
import com.buyeoon.member.application.CitizenCardQueryService;
import com.buyeoon.member.application.CitizenCardQueryService.BarcodeView;
import com.buyeoon.member.application.CitizenCardQueryService.CitizenCardOptionsView;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class CitizenCardController {

	private final CitizenCardQueryService citizenCards;
	private final CitizenCardCreator citizenCardCreation;

	public CitizenCardController(CitizenCardQueryService citizenCards, CitizenCardCreator citizenCardCreation) {
		this.citizenCards = citizenCards;
		this.citizenCardCreation = citizenCardCreation;
	}

	@GetMapping("/citizen-cards/options")
	public SuccessResponse<CitizenCardOptionsView> getOptions() {
		return SuccessResponse.of(citizenCards.getOptions());
	}

	@GetMapping("/citizen-cards/me")
	public SuccessResponse<CitizenCardView> getMyCard(@AuthenticationPrincipal Jwt jwt) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(citizenCards.getMyCard(memberId));
	}

	@GetMapping("/citizen-cards/me/barcode")
	public SuccessResponse<BarcodeView> getMyBarcode(@AuthenticationPrincipal Jwt jwt) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(citizenCards.getMyBarcode(memberId));
	}

	@PostMapping("/citizen-cards")
	public ResponseEntity<SuccessResponse<CitizenCardView>> create(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		CitizenCardView card = citizenCardCreation.create(memberId, idempotencyKey, parseRequest(request));
		return ResponseEntity.status(HttpStatus.CREATED).body(SuccessResponse.of(card));
	}

	private CitizenCardCommand parseRequest(JsonNode request) {
		if (request == null || !request.isObject()
				|| !hasOnlyProperties(request, Set.of("displayName", "characterId", "themeId", "location"))) {
			throw new InvalidCitizenCardRequestException();
		}
		String displayName = normalizedDisplayName(request.get("displayName"));
		UUID characterId = uuid(request.get("characterId"));
		UUID themeId = uuid(request.get("themeId"));
		LocationCommand location = location(request.get("location"));
		return new CitizenCardCommand(displayName, characterId, themeId, location);
	}

	private String normalizedDisplayName(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidCitizenCardRequestException();
		}
		String displayName = node.stringValue().strip();
		int length = displayName.codePointCount(0, displayName.length());
		if (length < 1 || length > 8 || displayName.codePoints().anyMatch(Character::isISOControl)) {
			throw new InvalidCitizenCardRequestException();
		}
		return displayName;
	}

	private UUID uuid(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidCitizenCardRequestException();
		}
		try {
			return UUID.fromString(node.stringValue());
		} catch (IllegalArgumentException exception) {
			throw new InvalidCitizenCardRequestException();
		}
	}

	private LocationCommand location(JsonNode node) {
		Set<String> fields = Set.of("latitude", "longitude", "accuracyMeters", "capturedAt");
		if (node == null || !node.isObject() || !hasOnlyOptionalProperties(node, fields, 3, 4)) {
			throw new InvalidCitizenCardRequestException();
		}
		double latitude = finiteNumber(node.get("latitude"));
		double longitude = finiteNumber(node.get("longitude"));
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw new InvalidCitizenCardRequestException();
		}
		Double accuracy = nullableAccuracy(node.get("accuracyMeters"));
		OffsetDateTime capturedAt = dateTime(node.get("capturedAt"));
		return new LocationCommand(latitude, longitude, accuracy, capturedAt);
	}

	private double finiteNumber(JsonNode node) {
		if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) {
			throw new InvalidCitizenCardRequestException();
		}
		return node.doubleValue();
	}

	private Double nullableAccuracy(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		double accuracy = finiteNumber(node);
		if (accuracy < 0) {
			throw new InvalidCitizenCardRequestException();
		}
		return accuracy;
	}

	private OffsetDateTime dateTime(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidCitizenCardRequestException();
		}
		try {
			return OffsetDateTime.parse(node.stringValue());
		} catch (DateTimeParseException exception) {
			throw new InvalidCitizenCardRequestException();
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
