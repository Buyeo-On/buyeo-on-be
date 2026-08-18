package com.buyeoon.mission.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 미션 완료 시 발행하는 애플리케이션 이벤트다(ADR-003). 배지 판정 리스너가 없어도 발행 자체는 완료 처리에 영향을 주지 않는다.
 */
public record MissionCompleted(UUID memberId, UUID tripId, UUID missionId, UUID participationId, Instant occurredAt) {
}
