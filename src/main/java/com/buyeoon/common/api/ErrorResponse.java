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

	public static ErrorResponse termVersionOutdated() {
		return new ErrorResponse(false, new ErrorData("TERM_VERSION_OUTDATED", "약관이 변경되었습니다. 현재 약관을 다시 확인해 주세요."));
	}

	public static ErrorResponse idempotencyKeyReused() {
		return new ErrorResponse(false, new ErrorData("IDEMPOTENCY_KEY_REUSED", "같은 멱등성 키를 다른 요청에 재사용할 수 없습니다."));
	}

	public static ErrorResponse requiredTermsNotAgreed() {
		return new ErrorResponse(false, new ErrorData("REQUIRED_TERMS_NOT_AGREED", "현재 필수 약관에 먼저 동의해 주세요."));
	}

	public static ErrorResponse outsideBuyeo() {
		return new ErrorResponse(false, new ErrorData("OUTSIDE_BUYEO", "부여 안에서만 군민증을 만들 수 있습니다."));
	}

	public static ErrorResponse invalidStateTransition() {
		return new ErrorResponse(false, new ErrorData("INVALID_STATE_TRANSITION", "현재 상태에서는 요청한 작업을 수행할 수 없습니다."));
	}

	public static ErrorResponse resourceNotFound() {
		return new ErrorResponse(false, new ErrorData("RESOURCE_NOT_FOUND", "대상을 찾을 수 없습니다."));
	}

	public record ErrorData(String code, String message) {
	}
}
