package com.buyeoon.place.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.place.application.PlaceAdminCommandService;
import com.buyeoon.place.application.PlaceAdminQueryService;
import com.buyeoon.place.entity.PlaceCategory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceAdminController {

	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 100;

	private final PlaceAdminCommandService placeAdminCommandService;
	private final PlaceAdminQueryService placeAdminQueryService;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public PlaceAdminController(PlaceAdminCommandService placeAdminCommandService,
			PlaceAdminQueryService placeAdminQueryService) {
		this.placeAdminCommandService = placeAdminCommandService;
		this.placeAdminQueryService = placeAdminQueryService;
	}

	@PostMapping("/admin/places")
	public SuccessResponse<Map<String, UUID>> create(@RequestBody PlaceAdminCreateRequest request) {
		UUID placeId = placeAdminCommandService.create(request);
		return SuccessResponse.of(Map.of("placeId", placeId));
	}

	@PutMapping("/admin/places/{placeId}")
	public SuccessResponse<Map<String, Object>> update(@PathVariable UUID placeId,
			@RequestBody PlaceAdminUpdateRequest request) {
		placeAdminCommandService.update(placeId, request);
		return SuccessResponse.of(Map.of());
	}

	@DeleteMapping("/admin/places/{placeId}")
	public SuccessResponse<Map<String, Object>> delete(@PathVariable UUID placeId) {
		placeAdminCommandService.delete(placeId);
		return SuccessResponse.of(Map.of());
	}

	@GetMapping("/admin/places")
	public SuccessResponse<PlaceAdminListView> list(@RequestParam(required = false) String category,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		if (size < MIN_SIZE || size > MAX_SIZE || page < 0) {
			throw new InvalidPlaceRequestException();
		}
		return SuccessResponse.of(placeAdminQueryService.list(parseCategory(category), page, size));
	}

	@GetMapping("/admin/places/{placeId}")
	public SuccessResponse<PlaceAdminView> get(@PathVariable UUID placeId) {
		return SuccessResponse.of(placeAdminQueryService.get(placeId));
	}

	private PlaceCategory parseCategory(String category) {
		if (category == null) {
			return null;
		}
		try {
			return PlaceCategory.valueOf(category);
		} catch (IllegalArgumentException exception) {
			throw new InvalidPlaceRequestException();
		}
	}
}
