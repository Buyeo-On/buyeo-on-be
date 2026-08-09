package com.buyeoon.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notifications")
public class NotificationEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "type", nullable = false, columnDefinition = "notification_type")
	private NotificationType type;

	@Column(name = "title", nullable = false, columnDefinition = "text")
	private String title;

	@Column(name = "body", nullable = false, columnDefinition = "text")
	private String body;

	@Column(name = "read_at")
	private Instant readAt;

	@Column(name = "target_type", columnDefinition = "text")
	private String targetType;

	@Column(name = "target_id")
	private UUID targetId;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	public static NotificationEntity create(
			UUID memberId,
			NotificationType type,
			String title,
			String body,
			String targetType,
			UUID targetId) {
		NotificationEntity notification = new NotificationEntity();
		notification.memberId = memberId;
		notification.type = type;
		notification.title = title;
		notification.body = body;
		notification.targetType = targetType;
		notification.targetId = targetId;
		return notification;
	}
}
