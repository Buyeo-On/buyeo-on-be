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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "social_accounts")
public class SocialAccountEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "provider", nullable = false, columnDefinition = "social_provider")
	private SocialProvider provider;

	@Column(name = "provider_subject", nullable = false, columnDefinition = "text")
	private String providerSubject;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public static SocialAccountEntity create(
			UUID memberId, SocialProvider provider, String providerSubject) {
		SocialAccountEntity account = new SocialAccountEntity();
		account.memberId = memberId;
		account.provider = provider;
		account.providerSubject = providerSubject;
		return account;
	}
}
