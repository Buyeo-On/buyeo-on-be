package com.buyeoon.member.auth.social;

public interface KakaoAuthorizationUnlinker {

	void verifyAndUnlink(KakaoSocialCredential credential, String expectedSubject);
}
