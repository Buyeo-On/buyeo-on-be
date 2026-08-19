package com.buyeoon.point.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_settlements")
public class PointSettlementEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "trip_id", nullable = false, unique = true)
	private UUID tripId;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "choice", nullable = false, columnDefinition = "settlement_choice")
	private SettlementChoice choice;

	@Column(name = "settled_points", nullable = false)
	private long settledPoints;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "settled_at", nullable = false, updatable = false)
	private Instant settledAt;

	@Column(name = "expired_at")
	private Instant expiredAt;

	public static PointSettlementEntity create(UUID tripId, SettlementChoice choice, long settledPoints,
			Instant settledAt) {
		PointSettlementEntity settlement = new PointSettlementEntity();
		settlement.tripId = tripId;
		settlement.choice = choice;
		settlement.settledPoints = settledPoints;
		settlement.settledAt = settledAt;
		settlement.expiresAt = choice == SettlementChoice.CARRY_OVER ? settledAt.plus(Duration.ofHours(240)) : null;
		return settlement;
	}
}
