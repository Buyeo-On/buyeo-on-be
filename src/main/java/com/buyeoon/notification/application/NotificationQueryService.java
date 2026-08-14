package com.buyeoon.notification.application;

import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.repository.NotificationProjection;
import com.buyeoon.notification.repository.NotificationQueryRepository;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class NotificationQueryService {

	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final NotificationQueryRepository notificationQueryRepository;

	public NotificationQueryService(NotificationQueryRepository notificationQueryRepository) {
		this.notificationQueryRepository = notificationQueryRepository;
	}

	public NotificationListView list(UUID memberId, NotificationCursor cursor, int size) {
		List<NotificationProjection> rows = cursor == null
				? notificationQueryRepository.findFromStart(memberId, PageRequest.of(0, size + 1))
				: notificationQueryRepository.findAfter(memberId, cursor.occurredAt(), cursor.notificationId(),
						PageRequest.of(0, size + 1));

		boolean hasNext = rows.size() > size;
		List<NotificationProjection> page = hasNext ? rows.subList(0, size) : rows;
		String nextCursor = hasNext
				? new NotificationCursor(page.getLast().occurredAt(), page.getLast().id()).encode()
				: null;
		List<NotificationItemView> items = page.stream().map(this::toView).toList();
		return new NotificationListView(items, new PageInfoView(nextCursor, hasNext));
	}

	private NotificationItemView toView(NotificationProjection row) {
		String targetId = row.targetId() == null ? null : row.targetId().toString();
		return new NotificationItemView(row.id(), row.type(), row.title(), row.body(), row.read(),
				row.occurredAt().atZone(ASIA_SEOUL), row.targetType(), targetId);
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
