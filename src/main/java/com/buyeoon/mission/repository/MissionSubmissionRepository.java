package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionSubmissionEntity;
import com.buyeoon.mission.entity.MissionType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionSubmissionRepository extends JpaRepository<MissionSubmissionEntity, UUID> {

	/**
	 * 회원이 전체 여행에서 정답으로 제출한 지정 유형 퀴즈 수를 센다({@code QUIZ_CORRECT_COUNT}, ADR-003).
	 */
	@Query("SELECT COUNT(s) FROM MissionSubmissionEntity s "
			+ "JOIN MissionParticipationEntity p ON p.id = s.participationId " + "JOIN TripEntity t ON t.id = p.tripId "
			+ "WHERE t.memberId = :memberId AND s.type IN :types AND s.correct = true")
	long countByMemberIdAndTypeInAndCorrectTrue(@Param("memberId") UUID memberId,
			@Param("types") Collection<MissionType> types);

	/**
	 * 회원이 정답으로 제출한 지정 유형 퀴즈를 제출 시각 내림차순, 여행 ID 오름차순으로 조회한다. Reconciliation의 최근 기여
	 * trip tie-breaker(ADR-003)에 사용하며 {@code pageable}로 최근 1건만 조회한다. 각 행은
	 * {@code [tripId, submittedAt]}이다.
	 */
	@Query("SELECT p.tripId, s.submittedAt FROM MissionSubmissionEntity s "
			+ "JOIN MissionParticipationEntity p ON p.id = s.participationId " + "JOIN TripEntity t ON t.id = p.tripId "
			+ "WHERE t.memberId = :memberId AND s.type IN :types AND s.correct = true "
			+ "ORDER BY s.submittedAt DESC, p.tripId ASC")
	List<Object[]> findLatestCorrectQuizContributionRows(@Param("memberId") UUID memberId,
			@Param("types") Collection<MissionType> types, Pageable pageable);

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
