package com.buyeoon.member.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class LogoutService {

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;

	public LogoutService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public void endCurrentSession(UUID memberId, UUID sessionId) {
		transactions.executeWithoutResult(status -> {
			lockActiveSession(memberId, sessionId);
			jdbcOperations.update("DELETE FROM push_tokens WHERE auth_session_id = ?", sessionId);
			int updated = jdbcOperations.update("""
					UPDATE auth_sessions
					SET revoked_at = CURRENT_TIMESTAMP
					WHERE id = ? AND revoked_at IS NULL
					""", sessionId);
			if (updated != 1) {
				throw new IllegalStateException("인증 세션을 종료할 수 없습니다.");
			}
		});
	}

	private void lockActiveSession(UUID memberId, UUID sessionId) {
		boolean sessionExists = !jdbcOperations.query("""
				SELECT session.id
				FROM auth_sessions session
				JOIN members member ON member.id = session.member_id
				WHERE session.id = ?
				  AND session.member_id = ?
				  AND session.expires_at > CURRENT_TIMESTAMP
				  AND session.revoked_at IS NULL
				  AND member.status = 'ACTIVE'
				FOR UPDATE OF session, member
				""", (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), sessionId, memberId).isEmpty();
		if (!sessionExists) {
			throw new AuthenticationCredentialsNotFoundException("활성 인증 세션이 아닙니다.");
		}
	}
}
