package com.buyeoon.member.application;

import com.buyeoon.member.entity.MemberStatus;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class MemberWithdrawalService {

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;

	public MemberWithdrawalService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public void withdraw(UUID memberId) {
		transactions.executeWithoutResult(status -> withdrawInTransaction(memberId));
	}

	private void withdrawInTransaction(UUID memberId) {
		if (lockMember(memberId) == MemberStatus.WITHDRAWN) {
			return;
		}
		int updated = jdbcOperations.update("""
				UPDATE members
				SET status = 'WITHDRAWN',
				    withdrawn_at = CURRENT_TIMESTAMP,
				    purge_after = CURRENT_TIMESTAMP + INTERVAL '30 days'
				WHERE id = ? AND status = 'ACTIVE'
				""", memberId);
		if (updated != 1) {
			throw new IllegalStateException("회원 탈퇴 상태를 저장할 수 없습니다.");
		}
		jdbcOperations.update("""
				DELETE FROM push_tokens
				WHERE auth_session_id IN (
				    SELECT id FROM auth_sessions WHERE member_id = ?
				)
				""", memberId);
		jdbcOperations.update("""
				UPDATE auth_sessions
				SET revoked_at = CURRENT_TIMESTAMP
				WHERE member_id = ? AND revoked_at IS NULL
				""", memberId);
		jdbcOperations.update("DELETE FROM social_accounts WHERE member_id = ?", memberId);
	}

	private MemberStatus lockMember(UUID memberId) {
		return jdbcOperations.query("""
				SELECT status::text
				FROM members
				WHERE id = ?
				FOR UPDATE
				""", (resultSet, rowNumber) -> MemberStatus.valueOf(resultSet.getString("status")), memberId).stream()
				.findFirst().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("회원이 없습니다."));
	}
}
