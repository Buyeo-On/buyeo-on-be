package com.buyeoon.badge;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 `03:00 Asia/Seoul`에 배지 reconciliation을 실행하는 Scheduler다(ADR-003). */
@Component
public class BadgeReconciliationScheduler {

	private final BadgeReconciliationService reconciliationService;

	public BadgeReconciliationScheduler(BadgeReconciliationService reconciliationService) {
		this.reconciliationService = reconciliationService;
	}

	@Scheduled(cron = "${badge.reconciliation.cron:0 0 3 * * *}", zone = "Asia/Seoul")
	public void reconcileActiveMembers() {
		reconciliationService.reconcile();
	}
}
