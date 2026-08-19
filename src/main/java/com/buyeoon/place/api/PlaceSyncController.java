package com.buyeoon.place.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.place.sync.PlaceSyncResult;
import com.buyeoon.place.sync.PlaceSyncService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceSyncController {

	private final PlaceSyncService placeSyncService;

	public PlaceSyncController(PlaceSyncService placeSyncService) {
		this.placeSyncService = placeSyncService;
	}

	@PostMapping("/admin/places/sync")
	public SuccessResponse<PlaceSyncResult> sync() {
		return SuccessResponse.of(placeSyncService.sync());
	}
}
