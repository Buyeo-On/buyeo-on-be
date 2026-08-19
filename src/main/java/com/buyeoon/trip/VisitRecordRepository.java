package com.buyeoon.trip;

import com.buyeoon.trip.entity.VisitRecordEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitRecordRepository extends JpaRepository<VisitRecordEntity, UUID> {

	@Query(value = "INSERT INTO visit_records (trip_id, mission_id, place_id) VALUES (:tripId, :missionId, :placeId) "
			+ "ON CONFLICT (trip_id, place_id) DO NOTHING RETURNING id", nativeQuery = true)
	Optional<UUID> insertIfAbsent(@Param("tripId") UUID tripId, @Param("missionId") UUID missionId,
			@Param("placeId") UUID placeId);

	long countByTripId(UUID tripId);
}
