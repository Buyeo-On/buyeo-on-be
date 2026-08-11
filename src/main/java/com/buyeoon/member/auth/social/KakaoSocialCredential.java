package com.buyeoon.member.auth.social;

public record KakaoSocialCredential(String accessToken) implements SocialCredential {

	@Override
	public String toString() {
		return "KakaoSocialCredential[accessToken=REDACTED]";
	}
}
