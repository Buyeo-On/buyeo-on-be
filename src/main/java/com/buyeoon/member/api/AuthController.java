package com.buyeoon.member.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.member.application.RefreshTokenRotationService;
import com.buyeoon.member.application.RefreshTokenRotationService.AuthResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final RefreshTokenRotationService refreshTokenRotationService;

	public AuthController(RefreshTokenRotationService refreshTokenRotationService) {
		this.refreshTokenRotationService = refreshTokenRotationService;
	}

	@PostMapping("/refresh")
	public SuccessResponse<AuthResult> refresh(@RequestBody RefreshTokenRequest request) {
		return SuccessResponse.of(refreshTokenRotationService.rotate(request.refreshToken()));
	}

	public record RefreshTokenRequest(String refreshToken) {

		@Override
		public String toString() {
			return "RefreshTokenRequest[refreshToken=REDACTED]";
		}
	}
}
