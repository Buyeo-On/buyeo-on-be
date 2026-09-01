package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionParticipationEntity;

public record SpecialQuizGeofenceProjection(MissionEntity mission, MissionParticipationEntity participation) {
}
