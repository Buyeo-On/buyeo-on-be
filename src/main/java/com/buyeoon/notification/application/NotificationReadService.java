package com.buyeoon.notification.application;

import com.buyeoon.notification.entity.NotificationEntity;
import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.repository.NotificationRepository;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationReadService {

	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final NotificationRepository notificationRepository;

	public NotificationReadService(NotificationRepository notificationRepository) {
		this.notificationRepository = notificationRepository;
	}

	@Transactional
	public NotificationView read(UUID memberId, UUID notificationId) {
		NotificationEntity notification = notificationRepository.findByIdAndMemberId(notificationId, memberId)
				.orElseThrow(NotificationNotFoundException::new);
		notification.markRead();
		return toView(notification);
	}

	private NotificationView toView(NotificationEntity notification) {
		return new NotificationView(notification.getId(), notification.getType(), notification.getTitle(),
				notification.getBody(), notification.getReadAt() != null,
				notification.getOccurredAt().atZone(ASIA_SEOUL), notification.getTargetType(),
				notification.getTargetId() == null ? null : notification.getTargetId().toString());
	}

	public record NotificationView(UUID notificationId, NotificationType type, String title, String body, boolean read,
			ZonedDateTime occurredAt, String targetType, String targetId) {
	}
}
