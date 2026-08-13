package com.buyeoon.member.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.member.application.LogoutService;
import com.buyeoon.member.application.RefreshTokenRotationService;
import com.buyeoon.member.application.RefreshTokenRotationService.AuthResult;
import com.buyeoon.member.application.SocialLoginService;
import com.buyeoon.member.auth.social.AppleSocialCredential;
import com.buyeoon.member.auth.social.KakaoSocialCredential;
import com.buyeoon.member.auth.social.SocialCredential;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final RefreshTokenRotationService refreshTokenRotationService;
	private final SocialLoginService socialLoginService;
	private final LogoutService logoutService;

	public AuthController(RefreshTokenRotationService refreshTokenRotationService,
			SocialLoginService socialLoginService, LogoutService logoutService) {
		this.refreshTokenRotationService = refreshTokenRotationService;
		this.socialLoginService = socialLoginService;
		this.logoutService = logoutService;
	}

	@PostMapping("/social-login")
	public SuccessResponse<SocialLoginService.AuthResult> socialLogin(@RequestBody JsonNode request) {
		return SuccessResponse.of(socialLoginService.login(parseCredential(request)));
	}

	@PostMapping("/refresh")
	public SuccessResponse<AuthResult> refresh(@RequestBody RefreshTokenRequest request) {
		return SuccessResponse.of(refreshTokenRotationService.rotate(request.refreshToken()));
	}

	@PostMapping("/logout")
	public SuccessResponse<Map<String, Object>> logout(@AuthenticationPrincipal Jwt jwt) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		UUID sessionId = UUID.fromString(Objects.requireNonNull(jwt.getClaimAsString("sid")));
		logoutService.endCurrentSession(memberId, sessionId);
		return SuccessResponse.of(Map.of());
	}

	public record RefreshTokenRequest(String refreshToken) {

		@Override
		public String toString() {
			return "RefreshTokenRequest[refreshToken=REDACTED]";
		}
	}

	private SocialCredential parseCredential(JsonNode request) {
		String provider = requiredText(request, "provider");
		return switch (provider) {
			case "KAKAO" -> {
				requireProperties(request, Set.of("provider", "accessToken"));
				yield new KakaoSocialCredential(requiredText(request, "accessToken"));
			}
			case "APPLE" -> {
				requireProperties(request, Set.of("provider", "authorizationCode", "identityToken", "nonce"));
				yield new AppleSocialCredential(requiredText(request, "authorizationCode"),
						requiredText(request, "identityToken"), requiredText(request, "nonce"));
			}
			default -> throw new InvalidSocialLoginRequestException();
		};
	}

	private String requiredText(JsonNode request, String property) {
		if (request == null || !request.isObject()) {
			throw new InvalidSocialLoginRequestException();
		}
		JsonNode value = request.get(property);
		if (value == null || !value.isString() || value.stringValue().isBlank()) {
			throw new InvalidSocialLoginRequestException();
		}
		return value.stringValue();
	}

	private void requireProperties(JsonNode request, Set<String> properties) {
		boolean hasOnlyAllowedProperties = request.size() == properties.size()
				&& request.properties().stream().allMatch(property -> properties.contains(property.getKey()));
		if (!hasOnlyAllowedProperties) {
			throw new InvalidSocialLoginRequestException();
		}
	}
}
