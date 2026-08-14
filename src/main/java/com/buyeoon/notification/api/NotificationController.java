package com.buyeoon.notification.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.notification.application.NotificationCursor;
import com.buyeoon.notification.application.NotificationQueryService;
import com.buyeoon.notification.application.NotificationQueryService.NotificationListView;
import com.buyeoon.notification.application.NotificationReadService;
import com.buyeoon.notification.application.NotificationReadService.NotificationView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

	private static final int DEFAULT_SIZE = 20;
	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 100;

	private final NotificationQueryService notificationQueryService;
	private final NotificationReadService notificationReadService;

	public NotificationController(NotificationQueryService notificationQueryService,
			NotificationReadService notificationReadService) {
		this.notificationQueryService = notificationQueryService;
		this.notificationReadService = notificationReadService;
	}

	@GetMapping("/members/me/notifications")
	public SuccessResponse<NotificationListView> getMyNotifications(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String size) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(notificationQueryService.list(memberId, parseCursor(cursor), parseSize(size)));
	}

	@PatchMapping("/members/me/notifications/{notificationId}")
	public SuccessResponse<NotificationView> readNotification(@AuthenticationPrincipal Jwt jwt,
			@PathVariable String notificationId, @RequestBody ReadNotificationRequest request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		if (!Boolean.TRUE.equals(request.read())) {
			throw new InvalidNotificationRequestException();
		}
		return SuccessResponse.of(notificationReadService.read(memberId, notificationId(notificationId)));
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

	private UUID notificationId(String value) {
		try {
			UUID notificationId = UUID.fromString(value);
			if (!notificationId.toString().equalsIgnoreCase(value)) {
				throw new InvalidNotificationRequestException();
			}
			return notificationId;
		} catch (IllegalArgumentException exception) {
			throw new InvalidNotificationRequestException();
		}
	}
}
