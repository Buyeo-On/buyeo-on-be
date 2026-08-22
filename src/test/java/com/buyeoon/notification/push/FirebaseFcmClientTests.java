package com.buyeoon.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.buyeoon.notification.entity.NotificationType;
import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 네트워크 호출 없이 FCM 메시지 구성 규칙과 응답 해석 규칙을 검증한다. */
class FirebaseFcmClientTests {

	private final FirebaseFcmClient client = new FirebaseFcmClient(null);

	@Test
	@DisplayName("UNREGISTERED가 반환된 토큰만 삭제 대상으로 반환하고 다른 오류 토큰은 유지한다")
	void mapsOnlyUnregisteredTokensForDeletion() throws Exception {
		FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
		SendResponse success = mock(SendResponse.class);
		when(success.isSuccessful()).thenReturn(true);
		SendResponse unregistered = mock(SendResponse.class);
		when(unregistered.isSuccessful()).thenReturn(false);
		when(unregistered.getException()).thenReturn(exceptionWith(MessagingErrorCode.UNREGISTERED));
		SendResponse internalError = mock(SendResponse.class);
		when(internalError.isSuccessful()).thenReturn(false);
		when(internalError.getException()).thenReturn(exceptionWith(MessagingErrorCode.INTERNAL));
		BatchResponse batchResponse = mock(BatchResponse.class);
		when(batchResponse.getResponses()).thenReturn(List.of(success, unregistered, internalError));
		when(batchResponse.getSuccessCount()).thenReturn(1);
		when(batchResponse.getFailureCount()).thenReturn(2);
		when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batchResponse);
		FirebaseFcmClient clientWithMock = new FirebaseFcmClient(firebaseMessaging);

		FcmSendResult result = clientWithMock.sendMulticast(List.of("active", "unregistered", "temporarily-failed"),
				new PushMessage(NotificationType.BADGE, "제목", "본문", null, null, null));

		assertThat(result.acceptedCount()).isEqualTo(1);
		assertThat(result.failedCount()).isEqualTo(2);
		assertThat(result.unregisteredTokens()).containsExactly("unregistered");
	}

	private FirebaseMessagingException exceptionWith(MessagingErrorCode errorCode) throws Exception {
		Method factory = FirebaseMessagingException.class.getDeclaredMethod("withMessagingErrorCode",
				com.google.firebase.FirebaseException.class, MessagingErrorCode.class);
		factory.setAccessible(true);
		com.google.firebase.FirebaseException base = new com.google.firebase.FirebaseException(ErrorCode.UNKNOWN, "실패",
				null);
		return (FirebaseMessagingException) factory.invoke(null, base, errorCode);
	}

	@Test
	@DisplayName("제목·본문은 notification payload에, 유형과 선택 식별자는 data payload에 담고 TTL·collapse 정책은 지정하지 않는다")
	void buildsMessageWithoutTtlOrCollapsePolicy() throws Exception {
		UUID notificationId = UUID.randomUUID();
		UUID targetId = UUID.randomUUID();
		PushMessage message = new PushMessage(NotificationType.BADGE, "새로운 배지를 획득했어요!", "탐험가 배지를 획득했어요.",
				notificationId, "BADGE", targetId);

		MulticastMessage multicastMessage = client.toMulticastMessage(List.of("token-1", "token-2"), message);

		Message perTokenMessage = firstMessage(multicastMessage);
		assertThat(call(perTokenMessage, "getNotification")).isNotNull();
		assertThat(data(perTokenMessage)).containsEntry("type", "BADGE")
				.containsEntry("notificationId", notificationId.toString()).containsEntry("targetType", "BADGE")
				.containsEntry("targetId", targetId.toString());
		assertThat(call(perTokenMessage, "getAndroidConfig")).isNull();
		assertThat(call(perTokenMessage, "getApnsConfig")).isNull();
		assertThat(call(perTokenMessage, "getWebpushConfig")).isNull();
	}

	@Test
	@DisplayName("notification ID와 target 정보가 없으면 해당 data 키를 담지 않는다")
	void omitsAbsentOptionalDataKeys() throws Exception {
		PushMessage message = new PushMessage(NotificationType.POINT, "제목", "본문", null, null, null);

		MulticastMessage multicastMessage = client.toMulticastMessage(List.of("token-1"), message);

		Message perTokenMessage = firstMessage(multicastMessage);
		assertThat(data(perTokenMessage)).containsEntry("type", "POINT").doesNotContainKeys("notificationId",
				"targetType", "targetId");
	}

	@SuppressWarnings("unchecked")
	private Message firstMessage(MulticastMessage multicastMessage) throws Exception {
		return ((List<Message>) call(multicastMessage, "getMessageList")).get(0);
	}

	private Object call(Object target, String methodName) throws Exception {
		Method method = target.getClass().getDeclaredMethod(methodName);
		method.setAccessible(true);
		return method.invoke(target);
	}

	@SuppressWarnings("unchecked")
	private java.util.Map<String, String> data(Message message) throws Exception {
		return (java.util.Map<String, String>) call(message, "getData");
	}
}
