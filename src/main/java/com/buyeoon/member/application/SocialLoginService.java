package com.buyeoon.member.application;

import com.buyeoon.member.application.MemberQueryService.MemberView;
import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.member.auth.RefreshTokenService;
import com.buyeoon.member.auth.RefreshTokenService.IssuedRefreshToken;
import com.buyeoon.member.auth.social.SocialAuthenticationFailedException;
import com.buyeoon.member.auth.social.SocialCredential;
import com.buyeoon.member.auth.social.SocialCredentialVerifier;
import com.buyeoon.member.auth.social.VerifiedSocialIdentity;
import com.buyeoon.member.entity.MemberStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SocialLoginService {

	private final JdbcOperations jdbcOperations;
	private final List<SocialCredentialVerifier> verifiers;
	private final RefreshTokenService refreshTokenService;
	private final AccessTokenService accessTokenService;
	private final MemberQueryService memberQueryService;
	private final TransactionTemplate transactions;

	public SocialLoginService(JdbcOperations jdbcOperations, List<SocialCredentialVerifier> verifiers,
			RefreshTokenService refreshTokenService, AccessTokenService accessTokenService,
			MemberQueryService memberQueryService, PlatformTransactionManager transactionManager) {
		this.jdbcOperations = jdbcOperations;
		this.verifiers = List.copyOf(verifiers);
		this.refreshTokenService = refreshTokenService;
		this.accessTokenService = accessTokenService;
		this.memberQueryService = memberQueryService;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public AuthResult login(SocialCredential credential) {
		VerifiedSocialIdentity identity = verifierFor(credential).verify(credential);
		if (identity.provider() != credential.provider() || identity.subject() == null
				|| identity.subject().isBlank()) {
			throw new SocialAuthenticationFailedException();
		}
		try {
			return required(transactions.execute(status -> loginOrRegister(identity)));
		} catch (DataIntegrityViolationException conflict) {
			AuthResult concurrentLogin = transactions.execute(status -> loginExisting(identity));
			if (concurrentLogin == null) {
				throw conflict;
			}
			return concurrentLogin;
		}
	}

	private SocialCredentialVerifier verifierFor(SocialCredential credential) {
		return verifiers.stream().filter(verifier -> verifier.provider() == credential.provider()).findFirst()
				.orElseThrow(SocialAuthenticationFailedException::new);
	}

	private AuthResult loginOrRegister(VerifiedSocialIdentity identity) {
		MemberState existing = findMember(identity);
		if (existing != null) {
			return issueSession(existing, false);
		}

		UUID memberId = UUID.randomUUID();
		jdbcOperations.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcOperations.update("""
				INSERT INTO social_accounts (member_id, provider, provider_subject)
				VALUES (?, ?::social_provider, ?)
				""", memberId, identity.provider().name(), identity.subject());
		jdbcOperations.update("INSERT INTO member_settings (member_id) VALUES (?)", memberId);
		return issueSession(new MemberState(memberId, MemberStatus.ACTIVE), true);
	}

	private AuthResult loginExisting(VerifiedSocialIdentity identity) {
		MemberState existing = findMember(identity);
		return existing == null ? null : issueSession(existing, false);
	}

	private MemberState findMember(VerifiedSocialIdentity identity) {
		return jdbcOperations.query("""
				SELECT member.id, member.status::text
				FROM social_accounts account
				JOIN members member ON member.id = account.member_id
				WHERE account.provider = ?::social_provider
				  AND account.provider_subject = ?
				""", this::mapMemberState, identity.provider().name(), identity.subject()).stream().findFirst()
				.orElse(null);
	}

	private AuthResult issueSession(MemberState member, boolean newMember) {
		if (member.status() == MemberStatus.WITHDRAWN) {
			throw new MemberWithdrawnException();
		}

		UUID sessionId = UUID.randomUUID();
		IssuedRefreshToken refreshToken = refreshTokenService.issue(sessionId);
		jdbcOperations.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, member.memberId(), refreshToken.hash(), Timestamp.from(refreshToken.expiresAt()));
		MemberView memberView = memberQueryService.getActiveMember(member.memberId());
		String accessToken = accessTokenService.issue(member.memberId(), sessionId);
		return new AuthResult(accessToken, refreshToken.token(), AccessTokenService.ACCESS_TOKEN_LIFETIME.toSeconds(),
				newMember, memberView);
	}

	private MemberState mapMemberState(ResultSet resultSet, int rowNumber) throws SQLException {
		return new MemberState(resultSet.getObject("id", UUID.class),
				MemberStatus.valueOf(resultSet.getString("status")));
	}

	private AuthResult required(AuthResult result) {
		return Objects.requireNonNull(result, "소셜 로그인 트랜잭션 결과가 없습니다.");
	}

	public record AuthResult(String accessToken, String refreshToken, long expiresInSeconds, boolean isNewMember,
			MemberView member) {

		@Override
		public String toString() {
			return "AuthResult[accessToken=REDACTED, refreshToken=REDACTED, expiresInSeconds=" + expiresInSeconds
					+ ", isNewMember=" + isNewMember + ", member=" + member + "]";
		}
	}

	private record MemberState(UUID memberId, MemberStatus status) {
	}
}
