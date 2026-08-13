package com.buyeoon.member.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.member.application.MemberQueryService;
import com.buyeoon.member.application.MemberQueryService.MemberView;
import com.buyeoon.member.application.MemberQueryService.SettingsView;
import com.buyeoon.member.application.MemberSettingsUpdateService;
import com.buyeoon.member.application.MemberSettingsUpdateService.SettingsUpdateCommand;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/members")
public class MemberController {

	private static final Set<String> SETTINGS_FIELDS = Set.of("nearbyQuizNotificationEnabled", "darkModeEnabled",
			"deviceNotificationPermissionGranted", "deviceLocationPermissionGranted", "version");

	private final MemberQueryService memberQueryService;
	private final MemberSettingsUpdateService memberSettingsUpdateService;

	public MemberController(MemberQueryService memberQueryService,
			MemberSettingsUpdateService memberSettingsUpdateService) {
		this.memberQueryService = memberQueryService;
		this.memberSettingsUpdateService = memberSettingsUpdateService;
	}

	@GetMapping("/me")
	public SuccessResponse<MemberView> getMyMember(@AuthenticationPrincipal Jwt jwt) {
		return SuccessResponse
				.of(memberQueryService.getActiveMember(UUID.fromString(Objects.requireNonNull(jwt.getSubject()))));
	}

	@GetMapping("/me/settings")
	public SuccessResponse<SettingsView> getMySettings(@AuthenticationPrincipal Jwt jwt) {
		return SuccessResponse
				.of(memberQueryService.getSettings(UUID.fromString(Objects.requireNonNull(jwt.getSubject()))));
	}

	@PatchMapping("/me/settings")
	public SuccessResponse<SettingsView> updateMySettings(@AuthenticationPrincipal Jwt jwt,
			@RequestBody JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(memberSettingsUpdateService.update(memberId, parseSettingsUpdate(request)));
	}

	private SettingsUpdateCommand parseSettingsUpdate(JsonNode request) {
		if (request == null || !request.isObject()
				|| request.properties().stream().anyMatch(property -> !SETTINGS_FIELDS.contains(property.getKey()))
				|| !request.has("version")
				|| (!request.has("nearbyQuizNotificationEnabled") && !request.has("darkModeEnabled"))) {
			throw new InvalidSettingsRequestException();
		}
		long version = version(request.get("version"));
		Boolean nearbyQuizNotificationEnabled = optionalBoolean(request, "nearbyQuizNotificationEnabled").orElse(null);
		Boolean darkModeEnabled = optionalBoolean(request, "darkModeEnabled").orElse(null);
		Boolean notificationPermission = optionalBoolean(request, "deviceNotificationPermissionGranted").orElse(null);
		Boolean locationPermission = optionalBoolean(request, "deviceLocationPermissionGranted").orElse(null);
		if (Boolean.TRUE.equals(nearbyQuizNotificationEnabled)
				&& (!Boolean.TRUE.equals(notificationPermission) || !Boolean.TRUE.equals(locationPermission))) {
			throw new InvalidSettingsRequestException();
		}
		return new SettingsUpdateCommand(nearbyQuizNotificationEnabled, darkModeEnabled, version);
	}

	private long version(JsonNode node) {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
			throw new InvalidSettingsRequestException();
		}
		return node.longValue();
	}

	private Optional<Boolean> optionalBoolean(JsonNode request, String field) {
		if (!request.has(field)) {
			return Optional.empty();
		}
		JsonNode value = request.get(field);
		if (value == null || !value.isBoolean()) {
			throw new InvalidSettingsRequestException();
		}
		return Optional.of(value.booleanValue());
	}
}
