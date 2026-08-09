package com.buyeoon.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "push_tokens")
public class PushTokenEntity {

	@Id
	@Column(name = "auth_session_id", nullable = false)
	private UUID authSessionId;

	@Column(name = "registration_token", nullable = false, unique = true, columnDefinition = "text")
	private String registrationToken;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp(source = SourceType.DB)
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public static PushTokenEntity create(UUID authSessionId, String registrationToken) {
		PushTokenEntity pushToken = new PushTokenEntity();
		pushToken.authSessionId = authSessionId;
		pushToken.registrationToken = registrationToken;
		return pushToken;
	}
}
