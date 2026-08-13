package com.buyeoon.member.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WithdrawnMemberDataPurgeSchedulerTests {

	@Test
	@DisplayName("스케줄 실행은 회원 정보 파기 서비스에 위임한다")
	void delegatesScheduledExecution() {
		WithdrawnMemberDataPurgeService purgeService = mock(WithdrawnMemberDataPurgeService.class);

		new WithdrawnMemberDataPurgeScheduler(purgeService).purgeDueMembers();

		verify(purgeService).purgeDueMembers();
	}
}
