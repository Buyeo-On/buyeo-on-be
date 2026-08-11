package com.buyeoon.common.api;

public record ErrorResponse(boolean success, ErrorData data) {

	public static ErrorResponse unauthorized() {
		return new ErrorResponse(false, new ErrorData("UNAUTHORIZED", "인증이 필요합니다."));
	}

	public record ErrorData(String code, String message) {
	}
}
