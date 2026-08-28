package com.buyeoon.badge.api;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.badge.application.BadgeAdminCommandService;
import com.buyeoon.badge.application.BadgeAdminQueryService;
import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.storage.PublicImageUploadUrlView;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BadgeAdminController {

	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 100;

	private final BadgeAdminCommandService badgeAdminCommandService;
	private final BadgeAdminQueryService badgeAdminQueryService;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public BadgeAdminController(BadgeAdminCommandService badgeAdminCommandService,
			BadgeAdminQueryService badgeAdminQueryService) {
		this.badgeAdminCommandService = badgeAdminCommandService;
		this.badgeAdminQueryService = badgeAdminQueryService;
	}

	@PostMapping("/admin/badges")
	public SuccessResponse<Map<String, UUID>> create(@RequestBody BadgeAdminCreateRequest request) {
		UUID badgeId = badgeAdminCommandService.create(request);
		return SuccessResponse.of(Map.of("badgeId", badgeId));
	}

	@PutMapping("/admin/badges/{badgeId}")
	public SuccessResponse<Map<String, Object>> update(@PathVariable UUID badgeId,
			@RequestBody BadgeAdminUpdateRequest request) {
		badgeAdminCommandService.update(badgeId, request);
		return SuccessResponse.of(Map.of());
	}

	@PostMapping("/admin/badges/{badgeId}/retire")
	public SuccessResponse<Map<String, Object>> retire(@PathVariable UUID badgeId) {
		badgeAdminCommandService.retire(badgeId);
		return SuccessResponse.of(Map.of());
	}

	@PostMapping("/admin/badges/{badgeId}/activate")
	public SuccessResponse<Map<String, Object>> activate(@PathVariable UUID badgeId) {
		badgeAdminCommandService.activate(badgeId);
		return SuccessResponse.of(Map.of());
	}

	@GetMapping("/admin/badges")
	public SuccessResponse<BadgeAdminListView> list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		if (size < MIN_SIZE || size > MAX_SIZE || page < 0) {
			throw new InvalidBadgeAdminRequestException();
		}
		return SuccessResponse.of(badgeAdminQueryService.list(page, size));
	}

	@GetMapping("/admin/badges/{badgeId}")
	public SuccessResponse<BadgeAdminView> get(@PathVariable UUID badgeId) {
		return SuccessResponse.of(badgeAdminQueryService.get(badgeId));
	}

	@GetMapping("/admin/badge-metrics")
	public SuccessResponse<List<BadgeMetric>> getBadgeMetrics() {
		return SuccessResponse.of(List.of(BadgeMetric.values()));
	}

	@PostMapping("/admin/badges/images/upload-url")
	public SuccessResponse<PublicImageUploadUrlView> createImageUploadUrl(
			@RequestBody BadgeAdminImageUploadUrlRequest request) {
		return SuccessResponse
				.of(badgeAdminCommandService.createImageUploadUrl(request.contentType(), request.fileSizeBytes()));
	}
}
