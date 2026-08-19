package com.buyeoon.point.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PointExpirationSchedulerTests {

	@Test
	@DisplayName("스케줄 실행은 포인트 만료 확정 서비스에 위임한다")
	void delegatesScheduledExecution() {
		PointExpirationService expirationService = mock(PointExpirationService.class);

		new PointExpirationScheduler(expirationService).expireDueCarryOvers();

		verify(expirationService).expireAllDue();
	}
}
