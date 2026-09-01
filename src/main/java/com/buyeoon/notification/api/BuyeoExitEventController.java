package com.buyeoon.notification.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.notification.application.BuyeoExitEventService;
import com.buyeoon.notification.application.BuyeoExitEventService.BuyeoExitEventCommand;
import com.buyeoon.notification.application.BuyeoExitEventService.BuyeoExitEventView;
import com.buyeoon.notification.application.BuyeoExitEventService.LocationCommand;
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

/** UC-28 부여 이탈 알림의 HTTP 진입점이다. */
@RestController
public class BuyeoExitEventController {

	private final BuyeoExitEventService buyeoExitEventService;

	/** 이탈 알림 커맨드 서비스를 주입한다. */
	public BuyeoExitEventController(BuyeoExitEventService buyeoExitEventService) {
		this.buyeoExitEventService = buyeoExitEventService;
	}

	/** 제출 위치를 검증한 뒤 이탈 알림 발송 여부를 반환한다. */
	@PostMapping("/notifications/buyeo-exit-events")
	public SuccessResponse<BuyeoExitEventView> notify(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(buyeoExitEventService.notify(memberId, idempotencyKey, parseRequest(request)));
	}

	/** OpenAPI Location 스키마만 허용하는 요청 본문을 파싱한다. */
	private BuyeoExitEventCommand parseRequest(JsonNode request) {
		if (request == null || !request.isObject() || !hasOnlyProperties(request, Set.of("location"))) {
			throw new InvalidNotificationRequestException();
		}
		return new BuyeoExitEventCommand(location(request.get("location")));
	}

	/** 위도·경도와 선택적 정확도, 측정 시각을 읽는다. */
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

	/** 유한한 JSON 숫자를 읽는다. */
	private double finiteNumber(JsonNode node) {
		if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) {
			throw new InvalidNotificationRequestException();
		}
		return node.doubleValue();
	}

	/** 음수가 아닌 정확도를 읽거나 생략을 허용한다. */
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

	/** ISO-8601 offset date-time을 파싱한다. */
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

	/** 허용된 필드만 정확히 가진 JSON 객체인지 확인한다. */
	private boolean hasOnlyProperties(JsonNode node, Set<String> properties) {
		return node.size() == properties.size()
				&& node.properties().stream().allMatch(property -> properties.contains(property.getKey()));
	}

	/** 필수 위치 필드와 선택적 정확도만 가진 JSON 객체인지 확인한다. */
	private boolean hasOnlyOptionalProperties(JsonNode node, Set<String> properties, int minimum, int maximum) {
		return node.size() >= minimum && node.size() <= maximum
				&& node.properties().stream().allMatch(property -> properties.contains(property.getKey()))
				&& node.has("latitude") && node.has("longitude") && node.has("capturedAt");
	}
}
