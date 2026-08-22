package com.buyeoon.member.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class PushTokenService {

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;

	public PushTokenService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public void upsert(UUID memberId, UUID sessionId, String registrationToken) {
		transactions.executeWithoutResult(status -> {
			lockActiveSession(memberId, sessionId);
			String currentToken = currentToken(sessionId);
			if (registrationToken.equals(currentToken)) {
				jdbcOperations.update("""
						UPDATE push_tokens
						SET updated_at = CURRENT_TIMESTAMP
						WHERE auth_session_id = ?
						""", sessionId);
				return;
			}
			jdbcOperations.update("DELETE FROM push_tokens WHERE auth_session_id = ?", sessionId);
			jdbcOperations.update("""
					INSERT INTO push_tokens (auth_session_id, registration_token)
					VALUES (?, ?)
					ON CONFLICT (registration_token) DO UPDATE
					SET auth_session_id = EXCLUDED.auth_session_id,
					    updated_at = CURRENT_TIMESTAMP
					""", sessionId, registrationToken);
		});
	}

	public void unregister(UUID memberId, UUID sessionId) {
		transactions.executeWithoutResult(status -> {
			lockActiveSession(memberId, sessionId);
			jdbcOperations.update("DELETE FROM push_tokens WHERE auth_session_id = ?", sessionId);
		});
	}

	private void lockActiveSession(UUID memberId, UUID sessionId) {
		lockActiveMember(memberId);
		boolean sessionExists = !jdbcOperations.query("""
				SELECT id
				FROM auth_sessions
				WHERE id = ?
				  AND member_id = ?
				  AND expires_at > CURRENT_TIMESTAMP
				  AND revoked_at IS NULL
				FOR UPDATE
				""", (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), sessionId, memberId).isEmpty();
		if (!sessionExists) {
			throw new AuthenticationCredentialsNotFoundException("활성 인증 세션이 아닙니다.");
		}
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

	private String currentToken(UUID sessionId) {
		return jdbcOperations.query("""
				SELECT registration_token
				FROM push_tokens
				WHERE auth_session_id = ?
				FOR UPDATE
				""", (resultSet, rowNumber) -> resultSet.getString("registration_token"), sessionId).stream()
				.findFirst().orElse(null);
	}
}
