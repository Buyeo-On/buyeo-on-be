package com.buyeoon.notification.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.notification.application.NotificationCursor;
import com.buyeoon.notification.application.NotificationQueryService;
import com.buyeoon.notification.application.NotificationQueryService.NotificationListView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

	private static final int DEFAULT_SIZE = 20;
	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 100;

	private final NotificationQueryService notificationQueryService;

	public NotificationController(NotificationQueryService notificationQueryService) {
		this.notificationQueryService = notificationQueryService;
	}

	@GetMapping("/members/me/notifications")
	public SuccessResponse<NotificationListView> getMyNotifications(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String size) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(notificationQueryService.list(memberId, parseCursor(cursor), parseSize(size)));
	}

	private NotificationCursor parseCursor(String cursor) {
		if (cursor == null) {
			return null;
		}
		try {
			return NotificationCursor.decode(cursor);
		} catch (IllegalArgumentException exception) {
			throw new InvalidNotificationRequestException();
		}
	}

	private int parseSize(String size) {
		if (size == null) {
			return DEFAULT_SIZE;
		}
		int value;
		try {
			value = Integer.parseInt(size);
		} catch (NumberFormatException exception) {
			throw new InvalidNotificationRequestException();
		}
		if (value < MIN_SIZE || value > MAX_SIZE) {
			throw new InvalidNotificationRequestException();
		}
		return value;
	}
}
