package com.buyeoon.notification.push;

import com.google.firebase.messaging.FirebaseMessagingException;

/** FCM 발송 요청이 실패했을 때 던지는 예외다. 비동기 executor에서 발생하며 업무 트랜잭션에는 영향을 주지 않는다. */
public class PushNotificationDeliveryException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public PushNotificationDeliveryException(FirebaseMessagingException cause) {
		super("FCM push 발송에 실패했습니다.", cause);
	}
}
