package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionParticipationEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionParticipationRepository extends JpaRepository<MissionParticipationEntity, UUID> {

	@Modifying
	@Query(value = "INSERT INTO mission_participations (trip_id, mission_id) VALUES (:tripId, :missionId) "
			+ "ON CONFLICT (trip_id, mission_id) DO NOTHING", nativeQuery = true)
	void insertIfAbsent(@Param("tripId") UUID tripId, @Param("missionId") UUID missionId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM MissionParticipationEntity p WHERE p.tripId = :tripId AND p.missionId = :missionId")
	Optional<MissionParticipationEntity> lockByTripIdAndMissionId(@Param("tripId") UUID tripId,
			@Param("missionId") UUID missionId);
}
