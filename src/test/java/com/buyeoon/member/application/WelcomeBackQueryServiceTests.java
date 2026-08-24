package com.buyeoon.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.buyeoon.badge.repository.MemberBadgeRepository;
import com.buyeoon.member.application.CitizenCardQueryService.BarcodeView;
import com.buyeoon.trip.VisitRecordRepository;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

class WelcomeBackQueryServiceTests {

	@Test
	void returnsThePreviousLoginAndAccumulatedRecords() {
		JdbcOperations jdbcOperations = mock(JdbcOperations.class);
		CitizenCardQueryService citizenCards = mock(CitizenCardQueryService.class);
		VisitRecordRepository visitRecords = mock(VisitRecordRepository.class);
		MemberBadgeRepository memberBadges = mock(MemberBadgeRepository.class);
		WelcomeBackQueryService service = new WelcomeBackQueryService(jdbcOperations, citizenCards, visitRecords,
				memberBadges);
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		ZonedDateTime lastVisitedAt = ZonedDateTime.of(2026, 7, 13, 14, 30, 0, 0, ZoneId.of("Asia/Seoul"));

		when(citizenCards.getMyBarcode(memberId)).thenReturn(new BarcodeView(null, "barcode", 2_800, true, ""));
		when(jdbcOperations.queryForObject(anyString(), any(RowMapper.class), eq(memberId), eq(sessionId)))
				.thenReturn(lastVisitedAt);
		when(visitRecords.countDistinctPlaceIdByMemberId(memberId)).thenReturn(3L);
		when(memberBadges.countByIdMemberId(memberId)).thenReturn(2L);

		WelcomeBackQueryService.WelcomeBackView result = service.get(memberId, sessionId);

		assertThat(result.lastVisitedAt()).isEqualTo(lastVisitedAt);
		assertThat(result.pointBalance()).isEqualTo(2_800);
		assertThat(result.visitedPlaceCount()).isEqualTo(3);
		assertThat(result.earnedBadgeCount()).isEqualTo(2);
	}
}
