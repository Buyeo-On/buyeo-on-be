package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionParticipationEntity;
import com.buyeoon.place.entity.PlaceEntity;

public record NearbyMissionProjection(
		MissionEntity mission,
		PlaceEntity place,
		double distanceMeters,
		MissionParticipationEntity participation) {
}
