package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.place.entity.PlaceEntity;

public record MissionPlaceDistanceProjection(MissionEntity mission, PlaceEntity place, double distanceMeters) {
}
