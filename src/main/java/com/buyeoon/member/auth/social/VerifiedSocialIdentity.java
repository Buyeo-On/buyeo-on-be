package com.buyeoon.member.auth.social;

import com.buyeoon.member.entity.SocialProvider;

public record VerifiedSocialIdentity(SocialProvider provider, String subject) {
}
