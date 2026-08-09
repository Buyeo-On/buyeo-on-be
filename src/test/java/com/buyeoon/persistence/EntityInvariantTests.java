package com.buyeoon.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.buyeoon.point.entity.PointSettlementEntity;
import com.buyeoon.point.entity.SettlementChoice;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityInvariantTests {

	@Test
	void carryOverExpirationUsesTheSettlementTimestamp() {
		Instant settledAt = Instant.parse("2026-08-10T00:00:00.123456Z");

		PointSettlementEntity settlement = PointSettlementEntity.create(
				UUID.randomUUID(), SettlementChoice.CARRY_OVER, 100L, settledAt);

		assertThat(settlement.getSettledAt()).isEqualTo(settledAt);
		assertThat(settlement.getExpiresAt()).isEqualTo(settledAt.plus(Duration.ofHours(240)));
	}

	@Test
	void leavingPointsDoesNotSetAnExpiration() {
		PointSettlementEntity settlement = PointSettlementEntity.create(
				UUID.randomUUID(), SettlementChoice.LEAVE_TO_BUYEO, 100L, Instant.now());

		assertThat(settlement.getExpiresAt()).isNull();
	}
}
