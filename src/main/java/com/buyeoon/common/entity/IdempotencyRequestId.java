package com.buyeoon.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

@Embeddable
public record IdempotencyRequestId(
		@Column(name = "member_id", nullable = false) UUID memberId,
		@Column(name = "idempotency_key", nullable = false, length = 128) String idempotencyKey) {}
