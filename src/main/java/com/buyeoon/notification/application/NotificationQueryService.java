package com.buyeoon.notification.application;

import com.buyeoon.notification.entity.NotificationType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

@Service
public class NotificationQueryService {

	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private static final String SELECT = """
			SELECT id, type::text AS type, title, body, (read_at IS NOT NULL) AS read,
			       occurred_at, target_type, target_id
			FROM notifications
			WHERE member_id = ?
			""";

	private final JdbcOperations jdbcOperations;

	public NotificationQueryService(JdbcOperations jdbcOperations) {
		this.jdbcOperations = jdbcOperations;
	}

	public NotificationListView list(UUID memberId, NotificationCursor cursor, int size) {
		List<NotificationRow> rows = cursor == null
				? notificationsFromStart(memberId, size)
				: notificationsAfterCursor(memberId, cursor, size);

		boolean hasNext = rows.size() > size;
		List<NotificationRow> page = hasNext ? rows.subList(0, size) : rows;
		String nextCursor = hasNext
				? new NotificationCursor(page.getLast().occurredAt(), page.getLast().id()).encode()
				: null;
		List<NotificationItemView> items = page.stream().map(this::toView).toList();
		return new NotificationListView(items, new PageInfoView(nextCursor, hasNext));
	}

	private List<NotificationRow> notificationsFromStart(UUID memberId, int size) {
		return jdbcOperations.query(SELECT + "ORDER BY occurred_at DESC, id DESC LIMIT ?", this::mapRow, memberId,
				size + 1);
	}

	private List<NotificationRow> notificationsAfterCursor(UUID memberId, NotificationCursor cursor, int size) {
		Timestamp occurredAt = Timestamp.from(cursor.occurredAt());
		return jdbcOperations.query(
				SELECT + "  AND (occurred_at, id) < (?, ?) ORDER BY occurred_at DESC, id DESC LIMIT ?", this::mapRow,
				memberId, occurredAt, cursor.notificationId(), size + 1);
	}

	private NotificationRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new NotificationRow(resultSet.getObject("id", UUID.class),
				NotificationType.valueOf(resultSet.getString("type")), resultSet.getString("title"),
				resultSet.getString("body"), resultSet.getBoolean("read"),
				resultSet.getTimestamp("occurred_at").toInstant(), resultSet.getString("target_type"),
				resultSet.getObject("target_id", UUID.class));
	}

	private NotificationItemView toView(NotificationRow row) {
		String targetId = row.targetId() == null ? null : row.targetId().toString();
		return new NotificationItemView(row.id(), row.type(), row.title(), row.body(), row.read(),
				row.occurredAt().atZone(ASIA_SEOUL), row.targetType(), targetId);
	}

	private record NotificationRow(UUID id, NotificationType type, String title, String body, boolean read,
			Instant occurredAt, String targetType, UUID targetId) {
	}

	public record NotificationListView(List<NotificationItemView> items, PageInfoView page) {
		public NotificationListView {
			items = List.copyOf(items);
		}
	}

	public record NotificationItemView(UUID notificationId, NotificationType type, String title, String body,
			boolean read, ZonedDateTime occurredAt, String targetType, String targetId) {
	}

	public record PageInfoView(String nextCursor, boolean hasNext) {
	}
}
