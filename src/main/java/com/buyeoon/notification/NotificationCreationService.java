package com.buyeoon.notification;

import com.buyeoon.notification.entity.NotificationEntity;
import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.repository.NotificationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 다른 도메인이 회원에게 알림을 남길 때 사용하는 notification 도메인의 공개 seam이다. */
@Service
public class NotificationCreationService {

	private final NotificationRepository notifications;

	public NotificationCreationService(NotificationRepository notifications) {
		this.notifications = notifications;
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
}
