package com.buyeoon.place.sync;

import java.util.List;

public record PlaceSyncResult(int successCount, int failureCount, List<String> failedContentIds) {

	public PlaceSyncResult {
		failedContentIds = List.copyOf(failedContentIds);
	}
}
