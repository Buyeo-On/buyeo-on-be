package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionSubmissionEntity;
import com.buyeoon.mission.entity.MissionType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionSubmissionRepository extends JpaRepository<MissionSubmissionEntity, UUID> {

	/**
	 * 회원이 전체 여행에서 제출한 퀴즈 답안을 제출 시각 오름차순으로 조회한다. {@code QUIZ_CORRECT_STREAK} 판정에
	 * 사용한다(ADR-003).
	 */
	@Query("SELECT new com.buyeoon.mission.repository.QuizSubmissionRow(s.correct, s.submittedAt, p.tripId) "
			+ "FROM MissionSubmissionEntity s JOIN MissionParticipationEntity p ON p.id = s.participationId "
			+ "JOIN TripEntity t ON t.id = p.tripId " + "WHERE t.memberId = :memberId AND s.type IN :types "
			+ "ORDER BY s.submittedAt ASC")
	List<QuizSubmissionRow> findQuizSubmissionsOrderedBySubmittedAtAsc(@Param("memberId") UUID memberId,
			@Param("types") Collection<MissionType> types);
}
