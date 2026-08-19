package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionParticipationEntity;
import com.buyeoon.mission.entity.MissionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

	/**
	 * 회원이 전체 여행에서 완료한 mission participation 수를 센다({@code MISSION_COMPLETED_COUNT},
	 * ADR-003).
	 */
	@Query("SELECT COUNT(p) FROM MissionParticipationEntity p JOIN TripEntity t ON t.id = p.tripId "
			+ "WHERE t.memberId = :memberId AND p.status = :status")
	long countByMemberIdAndStatus(@Param("memberId") UUID memberId, @Param("status") MissionStatus status);

	/**
	 * 회원이 완료한 mission participation을 완료 시각 내림차순, 여행 ID 오름차순으로 조회한다. Reconciliation의
	 * 최근 기여 trip tie-breaker(ADR-003)에 사용하며 {@code pageable}로 최근 1건만 조회한다.
	 */
	@Query("SELECT p FROM MissionParticipationEntity p JOIN TripEntity t ON t.id = p.tripId "
			+ "WHERE t.memberId = :memberId AND p.status = :status " + "ORDER BY p.completedAt DESC, p.tripId ASC")
	List<MissionParticipationEntity> findByMemberIdAndStatusOrderByCompletedAtDescTripIdAsc(
			@Param("memberId") UUID memberId, @Param("status") MissionStatus status, Pageable pageable);
}
