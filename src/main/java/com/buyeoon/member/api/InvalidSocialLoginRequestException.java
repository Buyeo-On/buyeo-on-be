package com.buyeoon.member.api;

public class InvalidSocialLoginRequestException extends RuntimeException {

	public InvalidSocialLoginRequestException() {
		super("소셜 로그인 요청 값이 올바르지 않습니다.");
	}
}
