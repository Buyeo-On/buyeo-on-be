package com.buyeoon.member.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class WithdrawnMemberDataPurgeScheduler {

	private final WithdrawnMemberDataPurgeService purgeService;

	public WithdrawnMemberDataPurgeScheduler(WithdrawnMemberDataPurgeService purgeService) {
		this.purgeService = purgeService;
	}

	@Scheduled(fixedDelayString = "${member.purge.interval:PT1M}", initialDelayString = "${member.purge.initial-delay:PT1M}")
	public void purgeDueMembers() {
		purgeService.purgeDueMembers();
	}
}
