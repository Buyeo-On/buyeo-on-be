package com.buyeoon.mission.api;

import com.buyeoon.mission.entity.MissionType;
import java.util.List;
import java.util.UUID;

public record MissionAdminView(UUID missionId, UUID placeId, MissionType type, String title, String description,
		int rewardPoints, Integer maxAttempts, Boolean oxCorrectAnswer, List<MissionAdminChoiceView> choices,
		double latitude, double longitude, boolean deleted) {
	public MissionAdminView {
		choices = List.copyOf(choices);
	}

	public record MissionAdminChoiceView(UUID choiceId, String label, boolean correct, int sortOrder) {
	}
}
