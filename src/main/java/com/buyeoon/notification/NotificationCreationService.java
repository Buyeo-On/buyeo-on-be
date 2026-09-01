package com.buyeoon.notification;

import com.buyeoon.notification.application.PushNotificationPublisher;
import com.buyeoon.notification.entity.NotificationEntity;
import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.repository.NotificationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 다른 도메인이 회원에게 알림을 남길 때 사용하는 notification 도메인의 공개 seam이다. */
@Service
public class NotificationCreationService {

	private final NotificationRepository notifications;
	private final PushNotificationPublisher pushNotificationPublisher;

	public NotificationCreationService(NotificationRepository notifications,
			PushNotificationPublisher pushNotificationPublisher) {
		this.notifications = notifications;
		this.pushNotificationPublisher = pushNotificationPublisher;
	}

	/** 새로 획득한 배지마다 {@code BADGE} 알림을 한 건 생성한다. */
	public void createBadgeAwarded(UUID memberId, UUID badgeId, String badgeName) {
		notifications.save(NotificationEntity.create(memberId, NotificationType.BADGE, "새로운 배지를 획득했어요!",
				badgeName + " 배지를 획득했어요.", "BADGE", badgeId));
	}

	/** 군민증을 발급할 때마다 {@code CITIZEN_CARD} 알림을 한 건 생성한다. */
	public void createCitizenCardIssued(UUID memberId, UUID cardId) {
		notifications.save(NotificationEntity.create(memberId, NotificationType.CITIZEN_CARD, "군민증이 발급됐어요!",
				"디지털 군민증이 발급됐어요.", "CITIZEN_CARD", cardId));
	}

	/**
	 * UC-27 검증을 모두 통과했을 때 {@code BUYEO_ENTRY} 알림을 생성하고, 배지 알림과 달리 즉시 FCM push도
	 * 발송한다.
	 */
	public void createBuyeoEntry(UUID memberId) {
		String title = "부여에 도착했어요!";
		String body = "지금 바로 여행을 시작해보세요.";
		NotificationEntity notification = notifications
				.save(NotificationEntity.create(memberId, NotificationType.BUYEO_ENTRY, title, body, null, null));
		pushNotificationPublisher.publish(memberId, NotificationType.BUYEO_ENTRY, title, body, notification.getId(),
				null, null);
	}

	/**
	 * UC-28 검증을 모두 통과했을 때 {@code BUYEO_EXIT} 알림을 생성하고 즉시 FCM push도 발송한다. 제목·본문은 임시
	 * 문구이며 별도 Design 이슈에서 확정한다.
	 */
	public void createBuyeoExit(UUID memberId) {
		String title = "부여를 떠났어요!";
		String body = "오늘의 여행을 마무리해보세요.";
		NotificationEntity notification = notifications
				.save(NotificationEntity.create(memberId, NotificationType.BUYEO_EXIT, title, body, null, null));
		pushNotificationPublisher.publish(memberId, NotificationType.BUYEO_EXIT, title, body, notification.getId(),
				null, null);
	}
}
