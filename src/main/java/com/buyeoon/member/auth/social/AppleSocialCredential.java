package com.buyeoon.member.auth.social;

import com.buyeoon.member.entity.SocialProvider;

public record AppleSocialCredential(String authorizationCode, String identityToken,
		String nonce) implements SocialCredential {

	@Override
	public SocialProvider provider() {
		return SocialProvider.APPLE;
	}

	@Override
	public String toString() {
		return "AppleSocialCredential[authorizationCode=REDACTED, identityToken=REDACTED, nonce=REDACTED]";
	}
}
