package com.buyeoon.member.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.member.application.MemberQueryService;
import com.buyeoon.member.application.MemberQueryService.MemberView;
import com.buyeoon.member.application.MemberQueryService.SettingsView;
import com.buyeoon.member.application.MemberSettingsUpdateService;
import com.buyeoon.member.application.MemberSettingsUpdateService.SettingsUpdateCommand;
import com.buyeoon.member.application.ProfileUpdateService;
import com.buyeoon.member.application.ProfileUpdateService.ProfileUpdateCommand;
import com.buyeoon.member.application.PushTokenService;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
	private final ProfileUpdateService profileUpdateService;
	private final PushTokenService pushTokenService;

	public MemberController(MemberQueryService memberQueryService,
			MemberSettingsUpdateService memberSettingsUpdateService, ProfileUpdateService profileUpdateService,
			PushTokenService pushTokenService) {
		this.memberQueryService = memberQueryService;
		this.memberSettingsUpdateService = memberSettingsUpdateService;
		this.profileUpdateService = profileUpdateService;
		this.pushTokenService = pushTokenService;
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

	@PatchMapping("/me/profile")
	public SuccessResponse<MemberView> updateMyProfile(@AuthenticationPrincipal Jwt jwt,
			@RequestBody JsonNode request) {
		return SuccessResponse.of(profileUpdateService.update(memberId(jwt), parseProfileUpdate(request)));
	}

	@PutMapping("/me/push-token")
	public SuccessResponse<Map<String, Object>> upsertMyPushToken(@AuthenticationPrincipal Jwt jwt,
			@RequestBody JsonNode request) {
		pushTokenService.upsert(memberId(jwt), sessionId(jwt), pushToken(request));
		return SuccessResponse.of(Map.of());
	}

	@DeleteMapping("/me/push-token")
	public SuccessResponse<Map<String, Object>> deleteMyPushToken(@AuthenticationPrincipal Jwt jwt) {
		pushTokenService.unregister(memberId(jwt), sessionId(jwt));
		return SuccessResponse.of(Map.of());
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

	private String pushToken(JsonNode request) {
		if (request == null || !request.isObject() || request.size() != 1 || !request.has("token")) {
			throw new InvalidPushTokenRequestException();
		}
		JsonNode tokenNode = request.get("token");
		if (tokenNode == null || !tokenNode.isString()) {
			throw new InvalidPushTokenRequestException();
		}
		String token = tokenNode.stringValue();
		if (token.isBlank() || token.codePointCount(0, token.length()) > 4_096) {
			throw new InvalidPushTokenRequestException();
		}
		return token;
	}

	private ProfileUpdateCommand parseProfileUpdate(JsonNode request) {
		Set<String> fields = Set.of("displayName", "characterId");
		if (request == null || !request.isObject() || request.isEmpty()
				|| request.properties().stream().anyMatch(property -> !fields.contains(property.getKey()))) {
			throw new InvalidProfileRequestException();
		}
		String displayName = request.has("displayName") ? displayName(request.get("displayName")) : null;
		UUID characterId = request.has("characterId") ? profileCharacterId(request.get("characterId")) : null;
		return new ProfileUpdateCommand(displayName, characterId);
	}

	private String displayName(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidProfileRequestException();
		}
		String displayName = node.stringValue().strip();
		int length = displayName.codePointCount(0, displayName.length());
		if (length < 1 || length > 8 || displayName.codePoints().anyMatch(Character::isISOControl)) {
			throw new InvalidProfileRequestException();
		}
		return displayName;
	}

	private UUID profileCharacterId(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new InvalidProfileRequestException();
		}
		try {
			return UUID.fromString(node.stringValue());
		} catch (IllegalArgumentException exception) {
			throw new InvalidProfileRequestException();
		}
	}

	private UUID memberId(Jwt jwt) {
		return UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
	}

	private UUID sessionId(Jwt jwt) {
		return UUID.fromString(Objects.requireNonNull(jwt.getClaimAsString("sid")));
	}
}
