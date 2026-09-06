package com.buyeoon.mission.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class MissionPhotoUploadCleanupScheduler {

	private final MissionPhotoUploadCleanupService cleanupService;

	public MissionPhotoUploadCleanupScheduler(MissionPhotoUploadCleanupService cleanupService) {
		this.cleanupService = cleanupService;
	}

	@Scheduled(fixedDelayString = "${mission.photo-upload-cleanup.interval:PT1H}", initialDelayString = "${mission.photo-upload-cleanup.initial-delay:PT1H}")
	public void purgeDueUploads() {
		cleanupService.purgeDueUploads();
	}
}
