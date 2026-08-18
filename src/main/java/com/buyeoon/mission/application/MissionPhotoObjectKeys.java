package com.buyeoon.mission.application;

import java.util.UUID;

/** 발급 시점과 제출 시점이 같은 S3 객체 키를 유도할 수 있도록 계산 규칙을 한 곳에 둔다. */
final class MissionPhotoObjectKeys {

	private MissionPhotoObjectKeys() {
	}

	static String key(UUID tripId, UUID missionId, UUID photoId) {
		return "private/missions/" + tripId + "/" + missionId + "/" + photoId;
	}
}
