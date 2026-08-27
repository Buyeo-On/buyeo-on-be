package com.buyeoon.mission.api;

import java.util.List;

public record MissionAdminUpdateRequest(String title, String description, int rewardPoints, Integer maxAttempts,
		Boolean oxCorrectAnswer, List<MissionChoiceRequest> choices) {
}
