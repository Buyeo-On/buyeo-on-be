package com.buyeoon.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionStatus;
import com.buyeoon.point.entity.PointSettlementEntity;
import com.buyeoon.point.entity.SettlementChoice;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityInvariantTests {

	@Test
	void missionStatusOnlyContainsPersistentStates() {
		assertThat(MissionStatus.values()).extracting(Enum::name).containsExactly("AVAILABLE", "EXHAUSTED",
				"COMPLETED");
	}

	@Test
	void missionFactoriesApplySupportedAttemptLimits() {
		UUID placeId = UUID.randomUUID();

		MissionEntity unlimited = MissionEntity.multipleChoice(placeId, "Quiz", "Choose one", 100, null);
		MissionEntity limited = MissionEntity.ox(placeId, "OX", "True or false", 100, 3, true);
		MissionEntity photo = MissionEntity.photo(placeId, "Photo", "Take a photo", 100);

		assertThat(unlimited.getMaxAttempts()).isNull();
		assertThat(limited.getMaxAttempts()).isEqualTo(3);
		assertThat(photo.getMaxAttempts()).isNull();
	}

	@Test
	void quizMissionsRejectNonPositiveMaximumAttempts() {
		UUID placeId = UUID.randomUUID();

		assertThatThrownBy(() -> MissionEntity.multipleChoice(placeId, "Quiz", "Choose one", 100, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MissionEntity.ox(placeId, "OX", "True or false", 100, -1, true))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void carryOverExpirationUsesTheSettlementTimestamp() {
		Instant settledAt = Instant.parse("2026-08-10T00:00:00.123456Z");

		PointSettlementEntity settlement = PointSettlementEntity.create(UUID.randomUUID(), SettlementChoice.CARRY_OVER,
				100L, settledAt);

		assertThat(settlement.getSettledAt()).isEqualTo(settledAt);
		assertThat(settlement.getExpiresAt()).isEqualTo(settledAt.plus(Duration.ofHours(240)));
	}

	@Test
	void leavingPointsDoesNotSetAnExpiration() {
		PointSettlementEntity settlement = PointSettlementEntity.create(UUID.randomUUID(),
				SettlementChoice.LEAVE_TO_BUYEO, 100L, Instant.now());

		assertThat(settlement.getExpiresAt()).isNull();
	}
}
