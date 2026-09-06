package com.buyeoon.member.application;

import com.buyeoon.member.auth.social.AppleAuthorizationRevoker;
import com.buyeoon.member.auth.social.AppleSocialCredential;
import com.buyeoon.member.auth.social.KakaoAuthorizationUnlinker;
import com.buyeoon.member.auth.social.KakaoSocialCredential;
import com.buyeoon.member.auth.social.SocialCredential;
import com.buyeoon.member.auth.social.SocialProviderUnavailableException;
import com.buyeoon.member.entity.MemberStatus;
import com.buyeoon.member.entity.SocialProvider;
import java.util.Optional;
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
	private final Optional<AppleAuthorizationRevoker> appleAuthorizationRevoker;
	private final Optional<KakaoAuthorizationUnlinker> kakaoAuthorizationUnlinker;

	public MemberWithdrawalService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			Optional<AppleAuthorizationRevoker> appleAuthorizationRevoker,
			Optional<KakaoAuthorizationUnlinker> kakaoAuthorizationUnlinker) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.appleAuthorizationRevoker = appleAuthorizationRevoker;
		this.kakaoAuthorizationUnlinker = kakaoAuthorizationUnlinker;
	}

	public void withdraw(UUID memberId) {
		withdraw(memberId, null);
	}

	public void withdraw(UUID memberId, SocialCredential credential) {
		socialAccount(memberId).ifPresent(account -> unlinkSocialAccount(credential, account));
		transactions.executeWithoutResult(status -> withdrawInTransaction(memberId));
	}

	private Optional<SocialAccount> socialAccount(UUID memberId) {
		return jdbcOperations.query("""
				SELECT provider::text, provider_subject
				FROM social_accounts
				WHERE member_id = ?
				ORDER BY created_at
				LIMIT 1
				""",
				(resultSet, rowNumber) -> new SocialAccount(SocialProvider.valueOf(resultSet.getString("provider")),
						resultSet.getString("provider_subject")),
				memberId).stream().findFirst();
	}

	private void unlinkSocialAccount(SocialCredential credential, SocialAccount account) {
		if (account.provider() == SocialProvider.APPLE) {
			if (!(credential instanceof AppleSocialCredential appleCredential)) {
				throw new AppleReauthenticationRequiredException();
			}
			appleAuthorizationRevoker.orElseThrow(SocialProviderUnavailableException::new)
					.verifyAndRevoke(appleCredential, account.subject());
			return;
		}
		if (!(credential instanceof KakaoSocialCredential kakaoCredential)) {
			throw new KakaoReauthenticationRequiredException();
		}
		kakaoAuthorizationUnlinker.orElseThrow(SocialProviderUnavailableException::new).verifyAndUnlink(kakaoCredential,
				account.subject());
	}

	private record SocialAccount(SocialProvider provider, String subject) {
	}

	private void withdrawInTransaction(UUID memberId) {
		if (lockMember(memberId) == MemberStatus.WITHDRAWN) {
			return;
		}
		int updated = jdbcOperations.update("""
				UPDATE members
				SET status = 'WITHDRAWN',
				    withdrawn_at = CURRENT_TIMESTAMP,
				    purge_after = CURRENT_TIMESTAMP
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
