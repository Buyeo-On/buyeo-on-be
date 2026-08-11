package com.buyeoon.common.api;

public record ErrorResponse(boolean success, ErrorData data) {

	public static ErrorResponse unauthorized() {
		return new ErrorResponse(false, new ErrorData("UNAUTHORIZED", "인증이 필요합니다."));
	}

	public static ErrorResponse invalidRequest() {
		return new ErrorResponse(false, new ErrorData("INVALID_REQUEST", "요청 값이 올바르지 않습니다."));
	}

	public static ErrorResponse socialAuthenticationFailed() {
		return new ErrorResponse(false, new ErrorData("SOCIAL_AUTHENTICATION_FAILED", "소셜 인증에 실패했습니다."));
	}

	public static ErrorResponse socialProviderUnavailable() {
		return new ErrorResponse(false, new ErrorData("SOCIAL_PROVIDER_UNAVAILABLE", "소셜 로그인을 일시적으로 사용할 수 없습니다."));
	}

	public static ErrorResponse memberWithdrawn() {
		return new ErrorResponse(false, new ErrorData("MEMBER_WITHDRAWN", "탈퇴한 회원은 로그인할 수 없습니다."));
	}

	public record ErrorData(String code, String message) {
	}
}
