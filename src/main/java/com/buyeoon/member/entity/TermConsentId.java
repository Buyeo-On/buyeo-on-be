package com.buyeoon.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

@Embeddable
public record TermConsentId(
		@Column(name = "member_id", nullable = false) UUID memberId,
		@Column(name = "term_id", nullable = false) UUID termId) {}
