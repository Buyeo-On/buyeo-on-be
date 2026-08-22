package com.buyeoon.mission;

import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionSubmissionRepository;
import com.buyeoon.mission.repository.QuizSubmissionRow;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * badge 도메인이 {@code QUIZ_CORRECT_STREAK}를 판정할 때 사용하는 mission 도메인의 공개 query
 * seam이다(ADR-003).
 */
@Service
@Transactional(readOnly = true)
public class QuizCorrectStreakMetricQuery {

	private static final EnumSet<MissionType> QUIZ_TYPES = EnumSet.of(MissionType.MULTIPLE_CHOICE, MissionType.OX);

	private final MissionSubmissionRepository missionSubmissions;

	public QuizCorrectStreakMetricQuery(MissionSubmissionRepository missionSubmissions) {
		this.missionSubmissions = missionSubmissions;
	}

	/**
	 * 회원이 전체 여행에서 제출한 퀴즈(객관식·OX) 답안을 제출 시각 순으로 훑어 지금까지 달성한 최대 연속 정답 수와, 그 연속을 완성한
	 * 제출의 여행·시각을 계산한다. 오답이 나오면 연속이 끊기고 다른 여행의 제출이 섞여도 끊기지 않는다. 정답이 없으면 여행·시각은
	 * {@code null}이다.
	 */
	public QuizCorrectStreakMetricSnapshot snapshot(UUID memberId) {
		List<QuizSubmissionRow> submissions = missionSubmissions.findQuizSubmissionsOrderedBySubmittedAtAsc(memberId,
				QUIZ_TYPES);

		long currentStreak = 0;
		long maxStreak = 0;
		UUID latestTripId = null;
		Instant latestAnsweredAt = null;
		for (QuizSubmissionRow submission : submissions) {
			if (!submission.correct()) {
				currentStreak = 0;
				continue;
			}
			currentStreak += 1;
			if (currentStreak > maxStreak) {
				maxStreak = currentStreak;
				latestTripId = submission.tripId();
				latestAnsweredAt = submission.submittedAt();
			}
		}
		return new QuizCorrectStreakMetricSnapshot(maxStreak, latestTripId, latestAnsweredAt);
	}
}
