package com.buyeoon.member.application;

import com.buyeoon.badge.repository.MemberBadgeRepository;
import com.buyeoon.member.application.CitizenCardCreationService.CitizenCardView;
import com.buyeoon.member.application.CitizenCardQueryService.BarcodeView;
import com.buyeoon.trip.VisitRecordRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WelcomeBackQueryService {

	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final JdbcOperations jdbcOperations;
	private final CitizenCardQueryService citizenCards;
	private final VisitRecordRepository visitRecords;
	private final MemberBadgeRepository memberBadges;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public WelcomeBackQueryService(JdbcOperations jdbcOperations, CitizenCardQueryService citizenCards,
			VisitRecordRepository visitRecords, MemberBadgeRepository memberBadges) {
		this.jdbcOperations = jdbcOperations;
		this.citizenCards = citizenCards;
		this.visitRecords = visitRecords;
		this.memberBadges = memberBadges;
	}

	public WelcomeBackView get(UUID memberId, UUID currentSessionId) {
		BarcodeView barcode = citizenCards.getMyBarcode(memberId);
		ZonedDateTime lastVisitedAt = jdbcOperations.queryForObject("""
				SELECT MAX(created_at) AS last_visited_at
				FROM auth_sessions
				WHERE member_id = ?
				  AND id <> ?
				""", (resultSet, rowNumber) -> {
			Timestamp timestamp = resultSet.getTimestamp("last_visited_at");
			return timestamp == null ? null : timestamp.toInstant().atZone(ASIA_SEOUL);
		}, memberId, currentSessionId);
		return new WelcomeBackView(lastVisitedAt, barcode.citizenCard(), barcode.pointBalance(),
				visitRecords.countDistinctPlaceIdByMemberId(memberId), memberBadges.countByIdMemberId(memberId));
	}

	public record WelcomeBackView(ZonedDateTime lastVisitedAt, CitizenCardView citizenCard, long pointBalance,
			long visitedPlaceCount, long earnedBadgeCount) {
	}
}
