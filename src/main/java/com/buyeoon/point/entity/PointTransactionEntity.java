package com.buyeoon.point.entity;

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
@Table(name = "point_transactions")
public class PointTransactionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@Column(name = "trip_id")
	private UUID tripId;

	@Column(name = "participation_id")
	private UUID participationId;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "type", nullable = false, columnDefinition = "point_transaction_type")
	private PointTransactionType type;

	@Column(name = "amount", nullable = false)
	private long amount;

	@Column(name = "description", nullable = false, columnDefinition = "text")
	private String description;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	public static PointTransactionEntity create(
			UUID memberId,
			UUID tripId,
			UUID participationId,
			PointTransactionType type,
			long amount,
			String description) {
		validateAmount(type, amount);
		PointTransactionEntity transaction = new PointTransactionEntity();
		transaction.memberId = memberId;
		transaction.tripId = tripId;
		transaction.participationId = participationId;
		transaction.type = type;
		transaction.amount = amount;
		transaction.description = description;
		return transaction;
	}

	private static void validateAmount(PointTransactionType type, long amount) {
		boolean valid = switch (type) {
			case EARN -> amount > 0;
			case LEAVE_TO_BUYEO, EXPIRE -> amount < 0;
			case ADJUST -> amount != 0;
		};
		if (!valid) {
			throw new IllegalArgumentException("Point amount does not match transaction type");
		}
	}
}
