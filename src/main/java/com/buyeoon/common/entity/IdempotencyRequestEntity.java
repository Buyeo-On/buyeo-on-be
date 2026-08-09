package com.buyeoon.common.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "idempotency_requests")
public class IdempotencyRequestEntity {

	@EmbeddedId
	private IdempotencyRequestId id;

	@Column(name = "operation", nullable = false, columnDefinition = "text")
	private String operation;

	@Column(name = "request_hash", nullable = false, columnDefinition = "text")
	private String requestHash;

	@Column(name = "response_status")
	private Integer responseStatus;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "response_body", columnDefinition = "jsonb")
	private String responseBody;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	public static IdempotencyRequestEntity create(
			UUID memberId,
			String idempotencyKey,
			String operation,
			String requestHash,
			Instant expiresAt) {
		IdempotencyRequestEntity request = new IdempotencyRequestEntity();
		request.id = new IdempotencyRequestId(memberId, idempotencyKey);
		request.operation = operation;
		request.requestHash = requestHash;
		request.expiresAt = expiresAt;
		return request;
	}

	public void complete(int responseStatus, String responseBody) {
		this.responseStatus = responseStatus;
		this.responseBody = responseBody;
	}
}
