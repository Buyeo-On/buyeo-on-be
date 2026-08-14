package com.buyeoon.notification.repository;

import com.buyeoon.notification.entity.NotificationType;
import java.time.Instant;
import java.util.UUID;

public record NotificationProjection(UUID id, NotificationType type, String title, String body, Boolean read,
		Instant occurredAt, String targetType, UUID targetId) {
}
