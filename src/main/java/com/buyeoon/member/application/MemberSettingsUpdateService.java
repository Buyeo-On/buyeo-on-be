package com.buyeoon.member.application;

import com.buyeoon.member.application.MemberQueryService.SettingsView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemberSettingsUpdateService {

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;

	public MemberSettingsUpdateService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public SettingsView update(UUID memberId, SettingsUpdateCommand command) {
		return Objects.requireNonNull(transactions.execute(status -> updateInTransaction(memberId, command)),
				"서비스 설정 변경 결과가 없습니다.");
	}

	private SettingsView updateInTransaction(UUID memberId, SettingsUpdateCommand command) {
		lockActiveMember(memberId);
		SettingsView current = lockSettings(memberId);
		if (current.version() != command.version()) {
			throw new InvalidStateTransitionException();
		}
		boolean nearbyQuizNotificationEnabled = command.nearbyQuizNotificationEnabled() == null
				? current.nearbyQuizNotificationEnabled()
				: command.nearbyQuizNotificationEnabled();
		boolean darkModeEnabled = command.darkModeEnabled() == null
				? current.darkModeEnabled()
				: command.darkModeEnabled();
		if (nearbyQuizNotificationEnabled == current.nearbyQuizNotificationEnabled()
				&& darkModeEnabled == current.darkModeEnabled()) {
			return current;
		}
		int updated = jdbcOperations.update("""
				UPDATE member_settings
				SET nearby_quiz_notification_enabled = ?,
				    dark_mode_enabled = ?,
				    version = version + 1
				WHERE member_id = ?
				""", nearbyQuizNotificationEnabled, darkModeEnabled, memberId);
		if (updated != 1) {
			throw new IllegalStateException("서비스 설정을 변경할 수 없습니다.");
		}
		return new SettingsView(nearbyQuizNotificationEnabled, darkModeEnabled, current.version() + 1);
	}

	private void lockActiveMember(UUID memberId) {
		boolean memberExists = !jdbcOperations.query("""
				SELECT id
				FROM members
				WHERE id = ? AND status = 'ACTIVE'
				FOR UPDATE
				""", (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), memberId).isEmpty();
		if (!memberExists) {
			throw new AuthenticationCredentialsNotFoundException("활성 회원이 아닙니다.");
		}
	}

	private SettingsView lockSettings(UUID memberId) {
		return jdbcOperations
				.query("""
						SELECT nearby_quiz_notification_enabled,
						       dark_mode_enabled,
						       version
						FROM member_settings
						WHERE member_id = ?
						FOR UPDATE
						""",
						(resultSet, rowNumber) -> new SettingsView(
								resultSet.getBoolean("nearby_quiz_notification_enabled"),
								resultSet.getBoolean("dark_mode_enabled"), resultSet.getLong("version")),
						memberId)
				.stream().findFirst()
				.orElseThrow(() -> new AuthenticationCredentialsNotFoundException("서비스 설정이 없습니다."));
	}

	public record SettingsUpdateCommand(Boolean nearbyQuizNotificationEnabled, Boolean darkModeEnabled, long version) {
	}
}
