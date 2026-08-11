package com.buyeoon.member.auth.social;

public class SocialAuthenticationFailedException extends RuntimeException {

	public SocialAuthenticationFailedException() {
		super("소셜 인증 정보가 유효하지 않습니다.");
	}
}
