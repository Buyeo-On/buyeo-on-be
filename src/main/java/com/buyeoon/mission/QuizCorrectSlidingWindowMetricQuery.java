package com.buyeoon.mission;

import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionSubmissionRepository;
import com.buyeoon.mission.repository.MissionSubmissionRepository.QuizCorrectSubmissionProjection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * badge 도메인이 {@code QUIZ_CORRECT_WITHIN_60_MINUTES_COUNT}를 판정할 때 사용하는 mission
 * 도메인의 공개 query seam이다(ADR-003).
 */
@Service
@Transactional(readOnly = true)
public class QuizCorrectSlidingWindowMetricQuery {

	private static final Duration WINDOW = Duration.ofMinutes(60);
	private static final Set<MissionType> QUIZ_TYPES = Set.of(MissionType.MULTIPLE_CHOICE, MissionType.OX);

	private final MissionSubmissionRepository missionSubmissions;

	public QuizCorrectSlidingWindowMetricQuery(MissionSubmissionRepository missionSubmissions) {
		this.missionSubmissions = missionSubmissions;
	}

	/**
	 * 회원이 전체 여행에서 정답으로 제출한 퀴즈를 시간순으로 훑어 60분 이내에 들어오는 최대 연속 정답 수와, 그 구간을 만든 가장 최근
	 * 제출의 여행·시각을 계산한다. 정답 제출이 없으면 여행·시각은 {@code null}이다.
	 */
	public QuizCorrectSlidingWindowMetricSnapshot snapshot(UUID memberId) {
		List<QuizCorrectSubmissionProjection> submissions = missionSubmissions
				.findCorrectQuizSubmissionsOrderBySubmittedAtAsc(memberId, QUIZ_TYPES);
		if (submissions.isEmpty()) {
			return new QuizCorrectSlidingWindowMetricSnapshot(0, null, null);
		}

		long maxCount = 0;
		UUID latestTripId = null;
		Instant latestSubmittedAt = null;
		int left = 0;
		for (int right = 0; right < submissions.size(); right++) {
			Instant rightAt = submissions.get(right).getSubmittedAt();
			while (Duration.between(submissions.get(left).getSubmittedAt(), rightAt).compareTo(WINDOW) > 0) {
				left++;
			}
			long windowCount = right - left + 1;
			if (windowCount > maxCount) {
				maxCount = windowCount;
				latestTripId = submissions.get(right).getTripId();
				latestSubmittedAt = rightAt;
			}
		}
		return new QuizCorrectSlidingWindowMetricSnapshot(maxCount, latestTripId, latestSubmittedAt);
	}
}
