package com.buyeoon.mission.repository;

import java.time.Instant;
import java.util.UUID;

/** 인증 사진 제출 한 건의 여행 ID와 제출 시각이다(ADR-003). */
public record MissionPhotoSubmissionProjection(UUID tripId, Instant submittedAt) {
}
