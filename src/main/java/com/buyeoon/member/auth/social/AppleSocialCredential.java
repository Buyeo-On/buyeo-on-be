package com.buyeoon.member.auth.social;

public record AppleSocialCredential(String authorizationCode, String identityToken,
		String nonce) implements SocialCredential {

	@Override
	public String toString() {
		return "AppleSocialCredential[authorizationCode=REDACTED, identityToken=REDACTED, nonce=REDACTED]";
	}
}
