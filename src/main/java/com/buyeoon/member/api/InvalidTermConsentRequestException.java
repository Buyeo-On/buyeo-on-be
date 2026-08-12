package com.buyeoon.member.api;

public class InvalidTermConsentRequestException extends RuntimeException {

	public InvalidTermConsentRequestException() {
		super("약관 동의 요청 값이 올바르지 않습니다.");
	}
}
