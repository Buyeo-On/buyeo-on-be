package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.mission.QuizCorrectStreakMetricQuery;
import com.buyeoon.mission.QuizCorrectStreakMetricSnapshot;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * mission 도메인의 공개 query seam으로 {@code QUIZ_CORRECT_STREAK}를 계산하는
 * Provider다(ADR-003).
 */
@Component
public class QuizCorrectStreakProvider implements BadgeMetricProvider {

	private final QuizCorrectStreakMetricQuery quizCorrectStreakMetricQuery;

	public QuizCorrectStreakProvider(QuizCorrectStreakMetricQuery quizCorrectStreakMetricQuery) {
		this.quizCorrectStreakMetricQuery = quizCorrectStreakMetricQuery;
	}

	@Override
	public BadgeMetric metric() {
		return BadgeMetric.QUIZ_CORRECT_STREAK;
	}

	@Override
	public BadgeMetricSnapshot calculate(UUID memberId) {
		QuizCorrectStreakMetricSnapshot snapshot = quizCorrectStreakMetricQuery.snapshot(memberId);
		return new BadgeMetricSnapshot(snapshot.streak(), snapshot.latestTripId(), snapshot.latestAnsweredAt());
	}
}
