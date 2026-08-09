package com.buyeoon.badge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

@Embeddable
public record BadgeConditionId(
		@Column(name = "badge_id", nullable = false) UUID badgeId,
		@Column(name = "metric_key", nullable = false, columnDefinition = "text") String metricKey) {}
