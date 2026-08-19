package com.buyeoon.trip.entity;

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
@Table(name = "trips")
public class TripEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false, columnDefinition = "trip_status")
	private TripStatus status = TripStatus.IN_PROGRESS;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "settled_at")
	private Instant settledAt;

	public static TripEntity start(UUID memberId) {
		TripEntity trip = new TripEntity();
		trip.memberId = memberId;
		return trip;
	}

	/** 진행 중인 여행을 종료 상태로 전이하고 종료 시각을 기록한다. */
	public void end(Instant endedAt) {
		this.status = TripStatus.ENDED;
		this.endedAt = endedAt;
	}
}
