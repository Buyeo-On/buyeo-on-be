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
import org.hibernate.annotations.SourceType;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "visit_records")
public class VisitRecordEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "trip_id", nullable = false)
	private UUID tripId;

	@Column(name = "mission_id", nullable = false)
	private UUID missionId;

	@Column(name = "place_id", nullable = false)
	private UUID placeId;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "visited_at", nullable = false, updatable = false)
	private Instant visitedAt;

	public static VisitRecordEntity create(UUID tripId, UUID missionId, UUID placeId) {
		VisitRecordEntity visitRecord = new VisitRecordEntity();
		visitRecord.tripId = tripId;
		visitRecord.missionId = missionId;
		visitRecord.placeId = placeId;
		return visitRecord;
	}
}
