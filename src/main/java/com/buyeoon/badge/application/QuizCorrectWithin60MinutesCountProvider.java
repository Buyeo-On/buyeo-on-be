package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.mission.QuizCorrectSlidingWindowMetricQuery;
import com.buyeoon.mission.QuizCorrectSlidingWindowMetricSnapshot;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * mission 도메인의 공개 query seam으로 {@code QUIZ_CORRECT_WITHIN_60_MINUTES_COUNT}를
 * 계산하는 Provider다(ADR-003).
 */
@Component
public class QuizCorrectWithin60MinutesCountProvider implements BadgeMetricProvider {

	private final QuizCorrectSlidingWindowMetricQuery quizCorrectSlidingWindowMetricQuery;

	public QuizCorrectWithin60MinutesCountProvider(
			QuizCorrectSlidingWindowMetricQuery quizCorrectSlidingWindowMetricQuery) {
		this.quizCorrectSlidingWindowMetricQuery = quizCorrectSlidingWindowMetricQuery;
	}

	@Override
	public BadgeMetric metric() {
		return BadgeMetric.QUIZ_CORRECT_WITHIN_60_MINUTES_COUNT;
	}

	@Override
	public BadgeMetricSnapshot calculate(UUID memberId) {
		QuizCorrectSlidingWindowMetricSnapshot snapshot = quizCorrectSlidingWindowMetricQuery.snapshot(memberId);
		return new BadgeMetricSnapshot(snapshot.maxCorrectCount(), snapshot.latestTripId(),
				snapshot.latestSubmittedAt());
	}
}
