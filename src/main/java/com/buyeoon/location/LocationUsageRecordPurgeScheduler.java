package com.buyeoon.location;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class LocationUsageRecordPurgeScheduler {

	private final LocationUsageRecordPurgeService purgeService;

	public LocationUsageRecordPurgeScheduler(LocationUsageRecordPurgeService purgeService) {
		this.purgeService = purgeService;
	}

	@Scheduled(fixedDelayString = "${location.usage-record-purge.interval:PT1H}", initialDelayString = "${location.usage-record-purge.initial-delay:PT1H}")
	public void purgeExpired() {
		purgeService.purgeExpired();
	}
}
