package com.buyeoon.badge.api;

import java.util.List;

public record BadgeAdminCreateRequest(String category, String name, String description, String imageKey,
		String conditionText, boolean active, List<BadgeConditionRequest> conditions) {
}
