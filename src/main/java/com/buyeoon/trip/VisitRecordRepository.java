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

	/**
	 * 회원이 전체 여행에서 방문한 고유 문화재 수를 센다({@code HERITAGE_VISITED_COUNT}, ADR-003).
	 */
	@Query("SELECT COUNT(DISTINCT v.placeId) FROM VisitRecordEntity v JOIN TripEntity t ON t.id = v.tripId "
			+ "WHERE t.memberId = :memberId")
	long countDistinctPlaceIdByMemberId(@Param("memberId") UUID memberId);
}
