package com.buyeoon.notification.push;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * FCM 발송의 비활성 skip, accepted, 실패, 무효 토큰 삭제와 queue drop 결과를 메트릭으로 집계한다. 어떤 메트릭에도
 * 등록 토큰, 회원 ID나 알림 제목·본문을 태그·값으로 담지 않는다.
 */
@Component
public class PushNotificationMetrics {

	private final Counter skipped;
	private final Counter accepted;
	private final Counter failed;
	private final Counter invalidTokenDeleted;
	private final Counter queueDropped;

	public PushNotificationMetrics(MeterRegistry registry) {
		this.skipped = Counter.builder("push_notification.skipped").description("FCM 비활성화로 건너뛴 발송 대상 토큰 수")
				.register(registry);
		this.accepted = Counter.builder("push_notification.accepted").description("FCM이 접수한 토큰 수").register(registry);
		this.failed = Counter.builder("push_notification.failed").description("FCM 발송에 실패한 토큰 수").register(registry);
		this.invalidTokenDeleted = Counter.builder("push_notification.invalid_token_deleted")
				.description("UNREGISTERED로 삭제한 등록 토큰 수").register(registry);
		this.queueDropped = Counter.builder("push_notification.queue_dropped").description("executor 포화로 drop한 발송 요청 수")
				.register(registry);
	}

	public void recordSkipped(int count) {
		skipped.increment(count);
	}

	public void recordAccepted(int count) {
		accepted.increment(count);
	}

	public void recordFailed(int count) {
		failed.increment(count);
	}

	public void recordInvalidTokenDeleted(int count) {
		invalidTokenDeleted.increment(count);
	}

	public void recordQueueDropped() {
		queueDropped.increment();
	}
}
