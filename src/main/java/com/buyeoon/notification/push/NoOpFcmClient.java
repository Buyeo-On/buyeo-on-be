package com.buyeoon.notification.push;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code fcm.enabled=false}일 때 사용하는 비활성 구현이다. 어떤 발송 요청도 실제로 전달하지 않는다. */
public class NoOpFcmClient implements FcmClient {

	private static final Logger LOGGER = LoggerFactory.getLogger(NoOpFcmClient.class);

	private final PushNotificationMetrics metrics;

	public NoOpFcmClient(PushNotificationMetrics metrics) {
		this.metrics = metrics;
	}

	@Override
	public FcmSendResult sendMulticast(List<String> registrationTokens, PushMessage message) {
		LOGGER.info("FCM이 비활성화되어 발송을 건너뜁니다. tokenCount={}", registrationTokens.size());
		metrics.recordSkipped(registrationTokens.size());
		return new FcmSendResult(0, 0, List.of());
	}
}
