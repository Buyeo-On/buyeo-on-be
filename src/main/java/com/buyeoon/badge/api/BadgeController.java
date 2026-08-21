package com.buyeoon.badge.api;

import com.buyeoon.badge.application.BadgeQueryService;
import com.buyeoon.badge.application.BadgeQueryService.BadgeListView;
import com.buyeoon.badge.entity.BadgeCategory;
import com.buyeoon.common.api.SuccessResponse;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BadgeController {

	private final BadgeQueryService badgeQueryService;

	public BadgeController(BadgeQueryService badgeQueryService) {
		this.badgeQueryService = badgeQueryService;
	}

	@GetMapping("/members/me/badges")
	public SuccessResponse<BadgeListView> getMyBadges(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String category) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(badgeQueryService.list(memberId, parseCategory(category)));
	}

	private BadgeCategory parseCategory(String category) {
		if (category == null) {
			return null;
		}
		try {
			return BadgeCategory.valueOf(category);
		} catch (IllegalArgumentException exception) {
			throw new InvalidBadgeRequestException();
		}
	}
}
