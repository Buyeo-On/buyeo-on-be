package com.buyeoon.member.auth.social;

import com.buyeoon.member.entity.SocialProvider;

public record KakaoSocialCredential(String accessToken) implements SocialCredential {

	@Override
	public SocialProvider provider() {
		return SocialProvider.KAKAO;
	}

	@Override
	public String toString() {
		return "KakaoSocialCredential[accessToken=REDACTED]";
	}
}
