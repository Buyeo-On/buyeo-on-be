package com.buyeoon.notification.application;

import com.buyeoon.member.application.PushTargetQueryService;
import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.push.FcmClient;
import com.buyeoon.notification.push.PushMessage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 후속 알림 기능이 공통 FCM push 발송을 요청할 때 사용하는 notification 도메인의 공개 seam이다. 트랜잭션 안의 요청은
 * commit 후에만, 트랜잭션 밖의 요청은 즉시 전용 bounded executor로 전달해 업무 트랜잭션과 분리된 비동기 발송을
 * 수행한다.
 */
@Service
public class PushNotificationPublisher {

	private static final int MAX_TOKENS_PER_BATCH = 500;

	private final PushTargetQueryService pushTargetQueryService;
	private final FcmClient fcmClient;
	private final Executor pushNotificationExecutor;

	public PushNotificationPublisher(PushTargetQueryService pushTargetQueryService, FcmClient fcmClient,
			Executor pushNotificationExecutor) {
		this.pushTargetQueryService = pushTargetQueryService;
		this.fcmClient = fcmClient;
		this.pushNotificationExecutor = pushNotificationExecutor;
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

	private void dispatch(UUID memberId, PushMessage message) {
		pushNotificationExecutor.execute(() -> send(memberId, message));
	}

	/** 발송 직전에 회원 도메인의 공개 seam으로 활성 기기 토큰을 조회하고 최대 500개씩 나누어 전송한다. */
	private void send(UUID memberId, PushMessage message) {
		List<String> tokens = pushTargetQueryService.findRegistrationTokens(memberId);
		RuntimeException failure = null;
		for (int start = 0; start < tokens.size(); start += MAX_TOKENS_PER_BATCH) {
			List<String> batch = tokens.subList(start, Math.min(start + MAX_TOKENS_PER_BATCH, tokens.size()));
			try {
				fcmClient.sendMulticast(batch, message);
			} catch (RuntimeException exception) {
				if (failure == null) {
					failure = exception;
				} else {
					failure.addSuppressed(exception);
				}
			}
		}
		if (failure != null) {
			throw failure;
		}
	}
}
