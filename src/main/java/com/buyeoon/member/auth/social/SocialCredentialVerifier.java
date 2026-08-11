package com.buyeoon.member.auth.social;

import com.buyeoon.member.entity.SocialProvider;

public interface SocialCredentialVerifier {

	SocialProvider provider();

	VerifiedSocialIdentity verify(SocialCredential credential);
}
