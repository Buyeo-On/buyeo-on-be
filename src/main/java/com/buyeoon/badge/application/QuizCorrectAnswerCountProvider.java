package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.mission.QuizCorrectAnswerMetricQuery;
import com.buyeoon.mission.QuizCorrectAnswerMetricSnapshot;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * mission 도메인의 공개 query seam으로 {@code QUIZ_CORRECT_COUNT}를 계산하는
 * Provider다(ADR-003, #185).
 */
@Component
public class QuizCorrectAnswerCountProvider implements BadgeMetricProvider {

	private final QuizCorrectAnswerMetricQuery quizCorrectAnswerMetricQuery;

	public QuizCorrectAnswerCountProvider(QuizCorrectAnswerMetricQuery quizCorrectAnswerMetricQuery) {
		this.quizCorrectAnswerMetricQuery = quizCorrectAnswerMetricQuery;
	}

	@Override
	public BadgeMetric metric() {
		return BadgeMetric.QUIZ_CORRECT_COUNT;
	}

	@Override
	public BadgeMetricSnapshot calculate(UUID memberId) {
		QuizCorrectAnswerMetricSnapshot snapshot = quizCorrectAnswerMetricQuery.snapshot(memberId);
		return new BadgeMetricSnapshot(snapshot.count(), snapshot.latestTripId(), snapshot.latestSubmittedAt());
	}
}
