package com.buyeoon.badge.api;

import java.util.List;

public record BadgeAdminUpdateRequest(String category, String name, String description, String imageKey,
		String conditionText, List<BadgeConditionRequest> conditions) {
}
