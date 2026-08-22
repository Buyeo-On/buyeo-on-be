package com.buyeoon.notification.application;

import com.buyeoon.member.application.PushTargetQueryService;
import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.push.FcmClient;
import com.buyeoon.notification.push.FcmSendResult;
import com.buyeoon.notification.push.PushMessage;
import com.buyeoon.notification.push.PushNotificationMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 후속 알림 기능이 공통 FCM push 발송을 요청할 때 사용하는 notification 도메인의 공개 seam이다. 트랜잭션 안의 요청은
 * commit 후에만, 트랜잭션 밖의 요청은 즉시 전용 bounded executor로 전달해 업무 트랜잭션과 분리된 비동기 발송을
 * 수행한다. FCM 부분 실패와 executor 포화는 이미 commit된 업무나 호출 API의 성공에 영향을 주지 않으며 재시도하지
 * 않는다.
 */
@Service
public class PushNotificationPublisher {

	private static final Logger LOGGER = LoggerFactory.getLogger(PushNotificationPublisher.class);
	private static final int MAX_TOKENS_PER_BATCH = 500;

	private final PushTargetQueryService pushTargetQueryService;
	private final FcmClient fcmClient;
	private final Executor pushNotificationExecutor;
	private final PushNotificationMetrics metrics;

	public PushNotificationPublisher(PushTargetQueryService pushTargetQueryService, FcmClient fcmClient,
			Executor pushNotificationExecutor, PushNotificationMetrics metrics) {
		this.pushTargetQueryService = pushTargetQueryService;
		this.fcmClient = fcmClient;
		this.pushNotificationExecutor = pushNotificationExecutor;
		this.metrics = metrics;
	}

	/** persistent notification ID나 target 정보가 없는 요청도 처리할 수 있도록 두 값 모두 선택적으로 받는다. */
	public void publish(UUID memberId, NotificationType type, String title, String body, UUID notificationId,
			String targetType, UUID targetId) {
		PushMessage message = new PushMessage(type, title, body, notificationId, targetType, targetId);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

				@Override
				public void afterCommit() {
					dispatch(memberId, message);
				}
			});
		} else {
			dispatch(memberId, message);
		}
	}

	/** executor가 포화 상태면 호출 thread에서 FCM을 실행하지 않고 새 요청을 drop한다. */
	private void dispatch(UUID memberId, PushMessage message) {
		try {
			pushNotificationExecutor.execute(() -> send(memberId, message));
		} catch (RejectedExecutionException exception) {
			LOGGER.warn("FCM 발송 executor가 포화되어 새 발송 요청을 drop합니다.");
			metrics.recordQueueDropped();
		}
	}

	/** 발송 직전에 회원 도메인의 공개 seam으로 활성 기기 토큰을 조회하고 최대 500개씩 나누어 전송한다. */
	private void send(UUID memberId, PushMessage message) {
		List<String> tokens = pushTargetQueryService.findRegistrationTokens(memberId);
		List<String> unregisteredTokens = new ArrayList<>();
		for (int start = 0; start < tokens.size(); start += MAX_TOKENS_PER_BATCH) {
			List<String> batch = tokens.subList(start, Math.min(start + MAX_TOKENS_PER_BATCH, tokens.size()));
			sendBatch(batch, message, unregisteredTokens);
		}
		deleteUnregisteredTokens(unregisteredTokens);
	}

	/** FCM 예외는 애플리케이션에서 재시도하지 않고 실패로 관측만 남긴다. */
	private void sendBatch(List<String> batch, PushMessage message, List<String> unregisteredTokens) {
		try {
			FcmSendResult result = fcmClient.sendMulticast(batch, message);
			metrics.recordAccepted(result.acceptedCount());
			metrics.recordFailed(result.failedCount());
			unregisteredTokens.addAll(result.unregisteredTokens());
			LOGGER.info("FCM 발송 결과. accepted={} failed={}", result.acceptedCount(), result.failedCount());
		} catch (RuntimeException exception) {
			metrics.recordFailed(batch.size());
			LOGGER.warn("FCM 발송에 실패했습니다. tokenCount={}", batch.size(), exception);
		}
	}

	/** 토큰 삭제 실패는 이미 접수된 발송 결과나 호출 업무에 영향을 주지 않는다. */
	private void deleteUnregisteredTokens(List<String> unregisteredTokens) {
		if (unregisteredTokens.isEmpty()) {
			return;
		}
		try {
			pushTargetQueryService.deleteRegistrationTokens(unregisteredTokens);
			metrics.recordInvalidTokenDeleted(unregisteredTokens.size());
			LOGGER.info("무효 등록 토큰을 삭제했습니다. count={}", unregisteredTokens.size());
		} catch (RuntimeException exception) {
			LOGGER.warn("무효 등록 토큰 삭제에 실패했습니다. count={}", unregisteredTokens.size(), exception);
		}
	}
}
