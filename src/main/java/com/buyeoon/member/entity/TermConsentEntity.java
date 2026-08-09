package com.buyeoon.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
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
@Table(name = "term_consents")
public class TermConsentEntity {

	@EmbeddedId
	private TermConsentId id;

	@Column(name = "agreed", nullable = false)
	private boolean agreed;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "agreed_at", nullable = false)
	private Instant agreedAt;

	public static TermConsentEntity create(UUID memberId, UUID termId, boolean agreed) {
		TermConsentEntity consent = new TermConsentEntity();
		consent.id = new TermConsentId(memberId, termId);
		consent.agreed = agreed;
		return consent;
	}
}
