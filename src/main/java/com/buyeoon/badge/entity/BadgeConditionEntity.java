package com.buyeoon.badge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "badge_conditions")
public class BadgeConditionEntity {

	@EmbeddedId
	private BadgeConditionId id;

	@Column(name = "threshold", nullable = false)
	private long threshold;

	public static BadgeConditionEntity create(UUID badgeId, String metricKey, long threshold) {
		BadgeConditionEntity condition = new BadgeConditionEntity();
		condition.id = new BadgeConditionId(badgeId, metricKey);
		condition.threshold = threshold;
		return condition;
	}
}
