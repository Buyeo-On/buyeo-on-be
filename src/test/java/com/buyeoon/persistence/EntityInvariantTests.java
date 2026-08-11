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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntityInvariantTests {

	/** 위치에 따라 계산할 수 있는 상태는 제외하고 실제로 저장할 미션 상태만 유지하도록 보장한다. */
	@Test
	@DisplayName("미션 상태에는 영속 상태만 존재한다")
	void missionStatusOnlyContainsPersistentStates() {
		assertThat(MissionStatus.values()).extracting(Enum::name).containsExactly("AVAILABLE", "EXHAUSTED",
				"COMPLETED");
	}

	/** 퀴즈는 선택적으로 시도 횟수를 제한할 수 있지만 사진 미션은 제한하지 않는 규칙을 검증한다. */
	@Test
	@DisplayName("미션 팩토리는 유형별로 지원하는 시도 횟수 제한을 적용한다")
	void missionFactoriesApplySupportedAttemptLimits() {
		UUID placeId = UUID.randomUUID();

		MissionEntity unlimited = MissionEntity.multipleChoice(placeId, "Quiz", "Choose one", 100, null);
		MissionEntity limited = MissionEntity.ox(placeId, "OX", "True or false", 100, 3, true);
		MissionEntity photo = MissionEntity.photo(placeId, "Photo", "Take a photo", 100);

		assertThat(unlimited.getMaxAttempts()).isNull();
		assertThat(limited.getMaxAttempts()).isEqualTo(3);
		assertThat(photo.getMaxAttempts()).isNull();
	}

	/** 퀴즈의 최대 시도 횟수는 무제한을 뜻하는 null이거나 양수여야 함을 보장한다. */
	@Test
	@DisplayName("퀴즈 미션은 0 이하의 최대 시도 횟수를 거부한다")
	void quizMissionsRejectNonPositiveMaximumAttempts() {
		UUID placeId = UUID.randomUUID();

		assertThatThrownBy(() -> MissionEntity.multipleChoice(placeId, "Quiz", "Choose one", 100, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MissionEntity.ox(placeId, "OX", "True or false", 100, -1, true))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** 이월 포인트의 만료 시각이 처리 시각이 아닌 정산 시각으로부터 정확히 10일 뒤인지 검증한다. */
	@Test
	@DisplayName("포인트 이월 만료 시각은 정산 시각을 기준으로 계산한다")
	void carryOverExpirationUsesTheSettlementTimestamp() {
		Instant settledAt = Instant.parse("2026-08-10T00:00:00.123456Z");

		PointSettlementEntity settlement = PointSettlementEntity.create(UUID.randomUUID(), SettlementChoice.CARRY_OVER,
				100L, settledAt);

		assertThat(settlement.getSettledAt()).isEqualTo(settledAt);
		assertThat(settlement.getExpiresAt()).isEqualTo(settledAt.plus(Duration.ofHours(240)));
	}

	/** 부여에 두고 가기로 정산한 포인트에는 만료 개념을 적용하지 않음을 보장한다. */
	@Test
	@DisplayName("부여에 두고 간 포인트에는 만료 시각을 설정하지 않는다")
	void leavingPointsDoesNotSetAnExpiration() {
		PointSettlementEntity settlement = PointSettlementEntity.create(UUID.randomUUID(),
				SettlementChoice.LEAVE_TO_BUYEO, 100L, Instant.now());

		assertThat(settlement.getExpiresAt()).isNull();
	}
}
