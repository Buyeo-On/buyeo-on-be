package com.buyeoon.point.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 포인트 요청이 없는 회원도 포함해 만료 도래 이월 포인트를 정기적으로 확정하는 point 도메인의 Scheduler다. */
@Component
public class PointExpirationScheduler {

	private final PointExpirationService expirationService;

	public PointExpirationScheduler(PointExpirationService expirationService) {
		this.expirationService = expirationService;
	}

	@Scheduled(fixedDelayString = "${point.expiration.interval:PT1M}", initialDelayString = "${point.expiration.initial-delay:PT1M}")
	public void expireDueCarryOvers() {
		expirationService.expireAllDue();
	}
}
