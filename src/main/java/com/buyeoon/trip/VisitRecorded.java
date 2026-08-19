package com.buyeoon.trip;

import java.time.Instant;
import java.util.UUID;

/**
 * 방문 기록 생성 시 발행하는 애플리케이션 이벤트다(ADR-003). 배지 판정 리스너가 없어도 발행 자체는 방문 기록 생성에 영향을 주지
 * 않는다.
 */
public record VisitRecorded(UUID memberId, UUID tripId, UUID visitId, Instant occurredAt) {
}
