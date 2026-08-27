package com.buyeoon.mission.api;

import java.util.List;

public record MissionAdminListView(List<MissionAdminView> items, int page, int size, long totalElements,
		int totalPages) {
	public MissionAdminListView {
		items = List.copyOf(items);
	}
}
