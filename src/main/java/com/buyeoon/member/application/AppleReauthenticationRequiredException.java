package com.buyeoon.member.application;

public class AppleReauthenticationRequiredException extends RuntimeException {

	public AppleReauthenticationRequiredException() {
		super("Apple 재인증이 필요합니다.");
	}
}
