package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionSubmissionEntity;
import com.buyeoon.mission.entity.MissionType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionSubmissionRepository extends JpaRepository<MissionSubmissionEntity, UUID> {

	/**
	 * 회원이 전체 여행에서 제출한 특정 유형의 mission submission 수를
	 * 센다({@code PHOTO_SUBMISSION_COUNT}, ADR-003).
	 */
	@Query("SELECT COUNT(s) FROM MissionSubmissionEntity s "
			+ "JOIN MissionParticipationEntity p ON p.id = s.participationId " + "JOIN TripEntity t ON t.id = p.tripId "
			+ "WHERE t.memberId = :memberId AND s.type = :type")
	long countByMemberIdAndType(@Param("memberId") UUID memberId, @Param("type") MissionType type);

	/**
	 * 회원이 제출한 특정 유형의 mission submission을 제출 시각 내림차순, 여행 ID 오름차순으로 조회한다.
	 * Reconciliation의 최근 기여 trip tie-breaker(ADR-003)에 사용하며 {@code pageable}로 최근
	 * 1건만 조회한다.
	 */
	@Query("SELECT new com.buyeoon.mission.repository.MissionPhotoSubmissionProjection(p.tripId, s.submittedAt) "
			+ "FROM MissionSubmissionEntity s " + "JOIN MissionParticipationEntity p ON p.id = s.participationId "
			+ "JOIN TripEntity t ON t.id = p.tripId " + "WHERE t.memberId = :memberId AND s.type = :type "
			+ "ORDER BY s.submittedAt DESC, p.tripId ASC")
	List<MissionPhotoSubmissionProjection> findByMemberIdAndTypeOrderBySubmittedAtDescTripIdAsc(
			@Param("memberId") UUID memberId, @Param("type") MissionType type, Pageable pageable);
}
