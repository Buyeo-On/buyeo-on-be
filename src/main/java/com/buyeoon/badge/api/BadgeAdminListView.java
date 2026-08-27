package com.buyeoon.badge.api;

import java.util.List;

public record BadgeAdminListView(List<BadgeAdminView> items, int page, int size, long totalElements,
		int totalPages) {
	public BadgeAdminListView {
		items = List.copyOf(items);
	}
}
