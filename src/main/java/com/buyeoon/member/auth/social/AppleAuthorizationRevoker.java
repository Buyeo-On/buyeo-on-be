package com.buyeoon.member.auth.social;

public interface AppleAuthorizationRevoker {

	void verifyAndRevoke(AppleSocialCredential credential, String expectedSubject);
}
