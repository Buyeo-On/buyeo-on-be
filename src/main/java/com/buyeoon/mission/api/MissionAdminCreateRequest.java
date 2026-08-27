package com.buyeoon.mission.api;

import java.util.List;
import java.util.UUID;

public record MissionAdminCreateRequest(UUID placeId, String type, String title, String description,
		int rewardPoints, Integer maxAttempts, Boolean oxCorrectAnswer, List<MissionChoiceRequest> choices) {
}
