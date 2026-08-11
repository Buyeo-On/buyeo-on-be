package com.buyeoon.member.auth.social;

public class SocialProviderUnavailableException extends RuntimeException {

	public SocialProviderUnavailableException() {
		super("소셜 인증 제공자를 일시적으로 사용할 수 없습니다.");
	}

	public SocialProviderUnavailableException(Throwable cause) {
		super("소셜 인증 제공자를 일시적으로 사용할 수 없습니다.", cause);
	}
}
