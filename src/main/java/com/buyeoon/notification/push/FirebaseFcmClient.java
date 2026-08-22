package com.buyeoon.notification.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import java.util.List;

/** Firebase Admin SDK로 실제 FCM 발송을 수행하는 구현이다. TTL과 collapse 정책은 별도로 지정하지 않는다. */
public class FirebaseFcmClient implements FcmClient {

	private final FirebaseMessaging firebaseMessaging;

	public FirebaseFcmClient(FirebaseMessaging firebaseMessaging) {
		this.firebaseMessaging = firebaseMessaging;
	}

	/** 제목·본문은 notification payload에, 유형과 선택 식별자는 data payload에 담아 발송한다. */
	@Override
	public void sendMulticast(List<String> registrationTokens, PushMessage message) {
		try {
			firebaseMessaging.sendEachForMulticast(toMulticastMessage(registrationTokens, message));
		} catch (FirebaseMessagingException exception) {
			throw new PushNotificationDeliveryException(exception);
		}
	}

	MulticastMessage toMulticastMessage(List<String> registrationTokens, PushMessage message) {
		MulticastMessage.Builder builder = MulticastMessage.builder().addAllTokens(registrationTokens)
				.setNotification(Notification.builder().setTitle(message.title()).setBody(message.body()).build())
				.putData("type", message.type().name());
		if (message.notificationId() != null) {
			builder.putData("notificationId", message.notificationId().toString());
		}
		if (message.targetType() != null) {
			builder.putData("targetType", message.targetType());
		}
		if (message.targetId() != null) {
			builder.putData("targetId", message.targetId().toString());
		}
		return builder.build();
	}
}
