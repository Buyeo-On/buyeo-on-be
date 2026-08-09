package com.buyeoon.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@Column(name = "refresh_token_hash", nullable = false, unique = true, columnDefinition = "text")
	private String refreshTokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public static AuthSessionEntity create(
			UUID memberId, String refreshTokenHash, Instant expiresAt) {
		AuthSessionEntity session = new AuthSessionEntity();
		session.memberId = memberId;
		session.refreshTokenHash = refreshTokenHash;
		session.expiresAt = expiresAt;
		return session;
	}
}
