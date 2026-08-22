package com.buyeoon.notification.push;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.List;

/** Firebase Admin SDK로 실제 FCM 발송을 수행하는 구현이다. TTL과 collapse 정책은 별도로 지정하지 않는다. */
public class FirebaseFcmClient implements FcmClient {

	private final FirebaseMessaging firebaseMessaging;

	public FirebaseFcmClient(FirebaseMessaging firebaseMessaging) {
		this.firebaseMessaging = firebaseMessaging;
	}

	/**
	 * 제목·본문은 notification payload에, 유형과 선택 식별자는 data payload에 담아 발송한다. 응답을 토큰별로 해석해
	 * {@code UNREGISTERED} 오류가 반환된 토큰만 삭제 대상으로 반환한다.
	 */
	@Override
	public FcmSendResult sendMulticast(List<String> registrationTokens, PushMessage message) {
		BatchResponse response;
		try {
			response = firebaseMessaging.sendEachForMulticast(toMulticastMessage(registrationTokens, message));
		} catch (FirebaseMessagingException exception) {
			throw new PushNotificationDeliveryException(exception);
		}
		List<String> unregisteredTokens = new ArrayList<>();
		List<SendResponse> responses = response.getResponses();
		for (int i = 0; i < responses.size(); i++) {
			SendResponse sendResponse = responses.get(i);
			if (!sendResponse.isSuccessful()
					&& sendResponse.getException().getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
				unregisteredTokens.add(registrationTokens.get(i));
			}
		}
		return new FcmSendResult(response.getSuccessCount(), response.getFailureCount(), unregisteredTokens);
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
