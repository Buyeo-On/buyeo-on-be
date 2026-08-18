package com.buyeoon.place.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.place.application.PlaceCommandService;
import com.buyeoon.place.application.PlaceCursor;
import com.buyeoon.place.application.PlaceQueryService;
import com.buyeoon.place.application.PlaceQueryService.PlaceItemView;
import com.buyeoon.place.application.PlaceQueryService.PlaceListView;
import com.buyeoon.place.application.SavedPlaceCursor;
import com.buyeoon.place.entity.PlaceCategory;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceController {

	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 100;

	private final PlaceQueryService placeQueryService;
	private final PlaceCommandService placeCommandService;

	public PlaceController(PlaceQueryService placeQueryService, PlaceCommandService placeCommandService) {
		this.placeQueryService = placeQueryService;
		this.placeCommandService = placeCommandService;
	}

	@PutMapping("/members/me/saved-places/{placeId}")
	public SuccessResponse<Map<String, Object>> savePlace(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID placeId) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		placeCommandService.savePlace(memberId, placeId);
		return SuccessResponse.of(Map.of());
	}

	@GetMapping("/places")
	public SuccessResponse<PlaceListView> getPlaces(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String category, @RequestParam String latitude,
			@RequestParam String longitude, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int size) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		if (size < MIN_SIZE || size > MAX_SIZE) {
			throw new InvalidPlaceRequestException();
		}
		return SuccessResponse.of(placeQueryService.list(memberId, parseCategory(category), latitude(latitude),
				longitude(longitude), parseCursor(cursor), size));
	}

	@GetMapping("/members/me/saved-places")
	public SuccessResponse<PlaceListView> getSavedPlaces(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String category, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int size) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		if (size < MIN_SIZE || size > MAX_SIZE) {
			throw new InvalidPlaceRequestException();
		}
		return SuccessResponse.of(
				placeQueryService.listSaved(memberId, parseCategory(category), parseSavedPlaceCursor(cursor), size));
	}

	@GetMapping("/places/{placeId}")
	public SuccessResponse<PlaceItemView> getPlace(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID placeId,
			@RequestParam String latitude, @RequestParam String longitude) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(placeQueryService.get(memberId, placeId, latitude(latitude), longitude(longitude)));
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

	private double latitude(String value) {
		double latitude = coordinate(value);
		if (latitude < -90 || latitude > 90) {
			throw new InvalidPlaceRequestException();
		}
		return latitude;
	}

	private double longitude(String value) {
		double longitude = coordinate(value);
		if (longitude < -180 || longitude > 180) {
			throw new InvalidPlaceRequestException();
		}
		return longitude;
	}

	private double coordinate(String value) {
		try {
			double coordinate = Double.parseDouble(value);
			if (!Double.isFinite(coordinate)) {
				throw new InvalidPlaceRequestException();
			}
			return coordinate;
		} catch (NumberFormatException exception) {
			throw new InvalidPlaceRequestException();
		}
	}

	private PlaceCursor parseCursor(String cursor) {
		if (cursor == null) {
			return null;
		}
		try {
			return PlaceCursor.decode(cursor);
		} catch (IllegalArgumentException exception) {
			throw new InvalidPlaceRequestException();
		}
	}

	private SavedPlaceCursor parseSavedPlaceCursor(String cursor) {
		if (cursor == null) {
			return null;
		}
		try {
			return SavedPlaceCursor.decode(cursor);
		} catch (IllegalArgumentException exception) {
			throw new InvalidPlaceRequestException();
		}
	}
}
