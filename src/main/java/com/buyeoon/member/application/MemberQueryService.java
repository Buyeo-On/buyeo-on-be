package com.buyeoon.member.application;

import com.buyeoon.member.entity.MemberStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class MemberQueryService {

	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final JdbcOperations jdbcOperations;

	public MemberQueryService(JdbcOperations jdbcOperations) {
		this.jdbcOperations = jdbcOperations;
	}

	public MemberView getActiveMember(UUID memberId) {
		return jdbcOperations.query("""
				SELECT member.id,
				       member.status::text,
				       profile.display_name,
				       profile.character_id,
				       COALESCE((
				           SELECT bool_and(COALESCE(consent.agreed, false))
				           FROM (
				               SELECT DISTINCT ON (term.type) term.id
				               FROM terms term
				               WHERE term.required = true
				                 AND term.effective_at <= CURRENT_TIMESTAMP
				               ORDER BY term.type, term.effective_at DESC
				           ) latest_required_term
				           LEFT JOIN term_consents consent
				             ON consent.term_id = latest_required_term.id
				            AND consent.member_id = member.id
				       ), false) AS required_terms_agreed,
				       EXISTS (
				           SELECT 1
				           FROM citizen_cards card
				           WHERE card.member_id = member.id
				       ) AS citizen_card_issued,
				       member.created_at
				FROM members member
				LEFT JOIN member_profiles profile ON profile.member_id = member.id
				WHERE member.id = ?
				  AND member.status = 'ACTIVE'
				""", this::mapMember, memberId).stream().findFirst()
				.orElseThrow(() -> new AuthenticationCredentialsNotFoundException("활성 회원이 아닙니다."));
	}

	public SettingsView getSettings(UUID memberId) {
		return jdbcOperations
				.query("""
						SELECT nearby_quiz_notification_enabled,
						       dark_mode_enabled,
						       version
						FROM member_settings
						WHERE member_id = ?
						""",
						(resultSet, rowNumber) -> new SettingsView(
								resultSet.getBoolean("nearby_quiz_notification_enabled"),
								resultSet.getBoolean("dark_mode_enabled"), resultSet.getLong("version")),
						memberId)
				.stream().findFirst()
				.orElseThrow(() -> new AuthenticationCredentialsNotFoundException("서비스 설정이 없습니다."));
	}

	private MemberView mapMember(ResultSet resultSet, int rowNumber) throws SQLException {
		return new MemberView(resultSet.getObject("id", UUID.class),
				MemberStatus.valueOf(resultSet.getString("status")), resultSet.getString("display_name"),
				resultSet.getObject("character_id", UUID.class), resultSet.getBoolean("required_terms_agreed"),
				resultSet.getBoolean("citizen_card_issued"),
				resultSet.getTimestamp("created_at").toInstant().atZone(ASIA_SEOUL));
	}

	public record MemberView(UUID memberId, MemberStatus status, String displayName, UUID characterId,
			boolean requiredTermsAgreed, boolean citizenCardIssued, ZonedDateTime createdAt) {
	}

	public record SettingsView(boolean nearbyQuizNotificationEnabled, boolean darkModeEnabled, long version) {
	}
}
