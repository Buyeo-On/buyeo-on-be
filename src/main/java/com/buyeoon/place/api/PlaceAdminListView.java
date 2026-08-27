package com.buyeoon.place.api;

import java.util.List;

public record PlaceAdminListView(List<PlaceAdminView> items, int page, int size, long totalElements,
		int totalPages) {
	public PlaceAdminListView {
		items = List.copyOf(items);
	}
}
