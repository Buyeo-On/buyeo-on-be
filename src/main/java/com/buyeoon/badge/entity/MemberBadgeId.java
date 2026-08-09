package com.buyeoon.badge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

@Embeddable
public record MemberBadgeId(
		@Column(name = "member_id", nullable = false) UUID memberId,
		@Column(name = "badge_id", nullable = false) UUID badgeId) {}
