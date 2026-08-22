package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionSubmissionEntity;
import com.buyeoon.mission.entity.MissionType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionSubmissionRepository extends JpaRepository<MissionSubmissionEntity, UUID> {

	/**
	 * 회원이 전체 여행에서 정답으로 제출한 퀴즈(객관식·OX)를 제출 시각 오름차순으로 조회한다({@code
	 * QUIZ_CORRECT_WITHIN_60_MINUTES_COUNT}, ADR-003).
	 */
	@Query("SELECT s.submittedAt AS submittedAt, p.tripId AS tripId FROM MissionSubmissionEntity s "
			+ "JOIN MissionParticipationEntity p ON p.id = s.participationId " + "JOIN TripEntity t ON t.id = p.tripId "
			+ "WHERE t.memberId = :memberId AND s.type IN :types AND s.correct = true " + "ORDER BY s.submittedAt ASC")
	List<QuizCorrectSubmissionProjection> findCorrectQuizSubmissionsOrderBySubmittedAtAsc(
			@Param("memberId") UUID memberId, @Param("types") Collection<MissionType> types);

	/** 정답 퀴즈 제출의 제출 시각과 소속 여행 ID다. */
	interface QuizCorrectSubmissionProjection {
		Instant getSubmittedAt();

		UUID getTripId();
	}
}
