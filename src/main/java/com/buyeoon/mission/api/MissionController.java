package com.buyeoon.mission.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.mission.application.MissionQueryService;
import com.buyeoon.mission.application.MissionQueryService.MissionListView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MissionController {

	private final MissionQueryService missionQueryService;

	public MissionController(MissionQueryService missionQueryService) {
		this.missionQueryService = missionQueryService;
	}

	@GetMapping("/missions/nearby")
	public SuccessResponse<MissionListView> getNearbyMissions(@AuthenticationPrincipal Jwt jwt,
			@RequestParam String latitude, @RequestParam String longitude, @RequestParam UUID tripId) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse
				.of(missionQueryService.listNearby(memberId, tripId, latitude(latitude), longitude(longitude)));
	}

	@GetMapping("/missions/{missionId}")
	public SuccessResponse<Object> getMission(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID missionId,
			@RequestParam String latitude, @RequestParam String longitude, @RequestParam UUID tripId) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(
				missionQueryService.getMission(memberId, missionId, tripId, latitude(latitude), longitude(longitude)));
	}

	private double latitude(String value) {
		double latitude = coordinate(value);
		if (latitude < -90 || latitude > 90) {
			throw new InvalidMissionRequestException();
		}
		return latitude;
	}

	private double longitude(String value) {
		double longitude = coordinate(value);
		if (longitude < -180 || longitude > 180) {
			throw new InvalidMissionRequestException();
		}
		return longitude;
	}

	private double coordinate(String value) {
		try {
			double coordinate = Double.parseDouble(value);
			if (!Double.isFinite(coordinate)) {
				throw new InvalidMissionRequestException();
			}
			return coordinate;
		} catch (NumberFormatException exception) {
			throw new InvalidMissionRequestException();
		}
	}
}
