package com.buyeoon.notification.push;

import com.buyeoon.notification.entity.NotificationType;
import java.util.UUID;

/** FCM으로 발송할 표시·이동 정보를 담는다. {@code notificationId}와 target 정보는 선택적이다. */
public record PushMessage(NotificationType type, String title, String body, UUID notificationId, String targetType,
		UUID targetId) {
}
