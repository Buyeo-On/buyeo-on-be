package com.buyeoon.mission.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "mission_participations")
public class MissionParticipationEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "trip_id", nullable = false)
	private UUID tripId;

	@Column(name = "mission_id", nullable = false)
	private UUID missionId;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false, columnDefinition = "mission_status")
	private MissionStatus status = MissionStatus.AVAILABLE;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "completed_at")
	private Instant completedAt;

	public static MissionParticipationEntity create(UUID tripId, UUID missionId) {
		MissionParticipationEntity participation = new MissionParticipationEntity();
		participation.tripId = tripId;
		participation.missionId = missionId;
		return participation;
	}
}
