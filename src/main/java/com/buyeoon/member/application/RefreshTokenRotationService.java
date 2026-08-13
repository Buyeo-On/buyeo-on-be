package com.buyeoon.member.application;

import com.buyeoon.member.application.MemberQueryService.MemberView;
import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.member.auth.InvalidRefreshTokenException;
import com.buyeoon.member.auth.RefreshTokenService;
import com.buyeoon.member.auth.RefreshTokenService.IssuedRefreshToken;
import com.buyeoon.member.auth.RefreshTokenService.ParsedRefreshToken;
import com.buyeoon.member.entity.MemberStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenRotationService {

	private final JdbcOperations jdbcOperations;
	private final RefreshTokenService refreshTokenService;
	private final AccessTokenService accessTokenService;
	private final MemberQueryService memberQueryService;

	public RefreshTokenRotationService(JdbcOperations jdbcOperations, RefreshTokenService refreshTokenService,
			AccessTokenService accessTokenService, MemberQueryService memberQueryService) {
		this.jdbcOperations = jdbcOperations;
		this.refreshTokenService = refreshTokenService;
		this.accessTokenService = accessTokenService;
		this.memberQueryService = memberQueryService;
	}

	@Transactional
	public AuthResult rotate(String rawRefreshToken) {
		ParsedRefreshToken parsed = refreshTokenService.parse(rawRefreshToken);
		UUID memberId = findMemberId(parsed.sessionId());
		lockMember(memberId);
		SessionState session = lockSession(parsed.sessionId(), memberId);
		Instant now = Instant.now();
		if (session.revokedAt() != null || !session.expiresAt().isAfter(now)
				|| session.memberStatus() != MemberStatus.ACTIVE
				|| !refreshTokenService.matches(session.refreshTokenHash(), parsed)) {
			throw new InvalidRefreshTokenException();
		}

		IssuedRefreshToken issuedRefreshToken = refreshTokenService.issue(session.sessionId());
		int updated = jdbcOperations.update("""
				UPDATE auth_sessions
				SET refresh_token_hash = ?, expires_at = ?
				WHERE id = ?
				""", issuedRefreshToken.hash(), Timestamp.from(issuedRefreshToken.expiresAt()), session.sessionId());
		if (updated != 1) {
			throw new IllegalStateException("인증 세션 갱신에 실패했습니다.");
		}

		MemberView member = memberQueryService.getActiveMember(session.memberId());
		String accessToken = accessTokenService.issue(session.memberId(), session.sessionId());
		return new AuthResult(accessToken, issuedRefreshToken.token(),
				AccessTokenService.ACCESS_TOKEN_LIFETIME.toSeconds(), false, member);
	}

	private UUID findMemberId(UUID sessionId) {
		return jdbcOperations
				.query("SELECT member_id FROM auth_sessions WHERE id = ?",
						(resultSet, rowNumber) -> resultSet.getObject("member_id", UUID.class), sessionId)
				.stream().findFirst().orElseThrow(InvalidRefreshTokenException::new);
	}

	private void lockMember(UUID memberId) {
		boolean exists = !jdbcOperations.query("SELECT id FROM members WHERE id = ? FOR UPDATE",
				(resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), memberId).isEmpty();
		if (!exists) {
			throw new InvalidRefreshTokenException();
		}
	}

	private SessionState lockSession(UUID sessionId, UUID memberId) {
		return jdbcOperations.query("""
				SELECT session.id,
				       session.member_id,
				       session.refresh_token_hash,
				       session.expires_at,
				       session.revoked_at,
				       member.status::text AS member_status
				FROM auth_sessions session
				JOIN members member ON member.id = session.member_id
				WHERE session.id = ?
				  AND session.member_id = ?
				FOR UPDATE OF session
				""", this::mapSession, sessionId, memberId).stream().findFirst()
				.orElseThrow(InvalidRefreshTokenException::new);
	}

	private SessionState mapSession(ResultSet resultSet, int rowNumber) throws SQLException {
		return new SessionState(resultSet.getObject("id", UUID.class), resultSet.getObject("member_id", UUID.class),
				resultSet.getString("refresh_token_hash"), resultSet.getTimestamp("expires_at").toInstant(),
				resultSet.getTimestamp("revoked_at") == null ? null : resultSet.getTimestamp("revoked_at").toInstant(),
				MemberStatus.valueOf(resultSet.getString("member_status")));
	}

	public record AuthResult(String accessToken, String refreshToken, long expiresInSeconds, boolean isNewMember,
			MemberView member) {

		@Override
		public String toString() {
			return "AuthResult[accessToken=REDACTED, refreshToken=REDACTED, expiresInSeconds=" + expiresInSeconds
					+ ", isNewMember=" + isNewMember + ", member=" + member + "]";
		}
	}

	private record SessionState(UUID sessionId, UUID memberId, String refreshTokenHash, Instant expiresAt,
			Instant revokedAt, MemberStatus memberStatus) {
	}
}
