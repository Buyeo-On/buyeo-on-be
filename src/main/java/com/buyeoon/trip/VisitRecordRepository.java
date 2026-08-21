package com.buyeoon.trip;

import com.buyeoon.trip.entity.VisitRecordEntity;
import java.util.List;
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

	/** 방문 시각 오름차순으로 여행의 방문 기록을 조회한다. */
	List<VisitRecordEntity> findByTripIdOrderByVisitedAtAsc(UUID tripId);

	/**
	 * 회원이 전체 여행에서 방문한 고유 문화재 수를 센다({@code HERITAGE_VISITED_COUNT}, ADR-003).
	 */
	@Query("SELECT COUNT(DISTINCT v.placeId) FROM VisitRecordEntity v JOIN TripEntity t ON t.id = v.tripId "
			+ "WHERE t.memberId = :memberId")
	long countDistinctPlaceIdByMemberId(@Param("memberId") UUID memberId);

	/**
	 * 고유 문화재 수에 기여한 각 문화재의 최초 방문 중 가장 최근 방문의 여행 ID·방문 시각을 조회한다. Reconciliation의 최근
	 * 기여 trip tie-breaker(ADR-003)에 사용한다. 방문 기록이 없으면 빈 목록을 반환한다.
	 */
	@Query(value = """
			SELECT first_visits.trip_id, first_visits.visited_at
			FROM (
			    SELECT DISTINCT ON (v.place_id) v.trip_id AS trip_id, v.visited_at AS visited_at
			    FROM visit_records v
			    JOIN trips t ON t.id = v.trip_id
			    WHERE t.member_id = :memberId
			    ORDER BY v.place_id, v.visited_at ASC, v.trip_id ASC
			) first_visits
			ORDER BY first_visits.visited_at DESC, first_visits.trip_id ASC
			LIMIT 1
			""", nativeQuery = true)
	List<Object[]> findLatestContributingFirstVisitRows(@Param("memberId") UUID memberId);
}
