package com.buyeoon.badge.api;

import com.buyeoon.badge.entity.BadgeCategory;
import java.util.List;
import java.util.UUID;

public record BadgeAdminView(UUID badgeId, BadgeCategory category, String name, String description, String imageUrl,
		String conditionText, boolean retired, List<BadgeAdminConditionView> conditions) {
	public BadgeAdminView {
		conditions = List.copyOf(conditions);
	}

	public record BadgeAdminConditionView(String metricKey, long threshold) {
	}
}
