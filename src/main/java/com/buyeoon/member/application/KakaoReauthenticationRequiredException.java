package com.buyeoon.member.application;

public class KakaoReauthenticationRequiredException extends RuntimeException {

	public KakaoReauthenticationRequiredException() {
		super("카카오 재인증이 필요합니다.");
	}
}
