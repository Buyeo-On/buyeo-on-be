package com.buyeoon.mission.repository;

import java.time.Instant;
import java.util.UUID;

/** {@code QUIZ_CORRECT_STREAK} 판정을 위한 퀴즈 제출 순서 projection이다(ADR-003). */
public record QuizSubmissionRow(boolean correct, Instant submittedAt, UUID tripId) {
}
