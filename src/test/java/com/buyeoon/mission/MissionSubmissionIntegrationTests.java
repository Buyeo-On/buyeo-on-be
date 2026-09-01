package com.buyeoon.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.mission.application.MissionCompleted;
import com.buyeoon.point.application.PointExpirationService;
import com.buyeoon.trip.VisitRecorded;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Testcontainers
class MissionSubmissionIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	// 시드 마이그레이션이 부여 지역에 장소·미션 예시 데이터를 채워두므로, 격리를 위해 남극 인근 좌표를
	// 기준으로 테스트 데이터를 배치한다.
	private static final double ORIGIN_LATITUDE = -75.0;
	private static final double ORIGIN_LONGITUDE = 0.0;

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private ApplicationEvents applicationEvents;

	@Autowired
	private PointExpirationService pointExpirationService;

	private AuthenticatedMember member;

	@BeforeEach
	void setUpMember() {
		member = insertAuthenticatedMember();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM point_settlements");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM visit_records");
		jdbcTemplate.update("DELETE FROM mission_submissions");
		jdbcTemplate.update("DELETE FROM mission_participations");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate
				.update("DELETE FROM mission_choices WHERE mission_id IN (SELECT id FROM missions WHERE place_id IN "
						+ "(SELECT id FROM places WHERE ST_Y(location::geometry) < -70))");
		jdbcTemplate.update(
				"DELETE FROM missions WHERE place_id IN (SELECT id FROM places WHERE ST_Y(location::geometry) < -70)");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** OX 미션에 정답을 제출하면 완료 처리되고 보상 포인트, 갱신된 잔액과 방문 기록을 받는다. */
	@Test
	@DisplayName("OX 미션에 정답을 제출하면 완료되고 보상·방문 기록을 받는다")
	void correctOxSubmissionCompletesMissionAndGrantsRewardAndVisit() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("OX 장소", 20);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "submit-ox-correct", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.missionId").value(missionId.toString()))
				.andExpect(jsonPath("$.data.completed").value(true))
				.andExpect(jsonPath("$.data.remainingAttempts").isEmpty())
				.andExpect(jsonPath("$.data.rewardPoints").value(100))
				.andExpect(jsonPath("$.data.pointBalance").value(100))
				.andExpect(jsonPath("$.data.visitRecorded").value(true))
				.andExpect(jsonPath("$.data.visitId").isNotEmpty());

		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM mission_participations WHERE trip_id = ? AND mission_id = ?", String.class, tripId,
				missionId)).isEqualTo("COMPLETED");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM visit_records WHERE trip_id = ? AND place_id = ?",
				Integer.class, tripId, place)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EARN'", Integer.class,
				member.memberId())).isEqualTo(1);

		assertThat(applicationEvents.stream(MissionCompleted.class)).hasSize(1);
		assertThat(applicationEvents.stream(VisitRecorded.class)).hasSize(1);
	}

	/** 미션 보상은 만료 도래 이월분을 먼저 EXPIRE로 확정한 뒤 EARN과 갱신 잔액을 반환한다. */
	@Test
	@DisplayName("만료 도래 이월분을 먼저 확정한 뒤 미션 보상과 갱신 잔액을 반환한다")
	void expiresDueCarryOverBeforeGrantingMissionReward() throws Exception {
		insertPointTransaction(member.memberId(), 300, Instant.now().minus(241, ChronoUnit.HOURS));
		UUID settledTripId = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(settledTripId, 200, Instant.now().minus(1, ChronoUnit.MINUTES));
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("만료 후 보상 장소", 20);
		UUID missionId = insertOxMission(place, "만료 후 보상 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "expire-before-reward", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.rewardPoints").value(100))
				.andExpect(jsonPath("$.data.pointBalance").value(200));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EXPIRE'", Integer.class,
				member.memberId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EARN'", Integer.class,
				member.memberId())).isEqualTo(2);
	}

	/** 미션 보상과 scheduler가 경합해도 만료 후 EARN 순서로 한 번씩 확정하고 음수 잔액을 만들지 않는다. */
	@Test
	@DisplayName("미션 보상과 scheduler 경합에도 만료 후 EARN 잔액을 직렬화한다")
	void missionRewardAndSchedulerSerializeExpirationBeforeEarn() throws Exception {
		insertPointTransaction(member.memberId(), 300, Instant.now().minus(241, ChronoUnit.HOURS));
		UUID settledTripId = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(settledTripId, 200, Instant.now().minus(1, ChronoUnit.MINUTES));
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("동시 만료 보상 장소", 20);
		UUID missionId = insertOxMission(place, "동시 만료 보상 미션", 100, null, true);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> reward = executor.submit(() -> concurrentSubmit(missionId, "concurrent-expire-reward",
					oxRequest(tripId, true), ready, start));
			Future<Integer> scheduler = executor.submit(() -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("동시 실행 시작 신호를 받지 못했습니다.");
				}
				return pointExpirationService.expireAllDue();
			});
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult result = reward.get(10, TimeUnit.SECONDS);
			scheduler.get(10, TimeUnit.SECONDS);
			assertThat(result.getResponse().getStatus()).isEqualTo(200);
			assertThat(JsonPath.<Integer>read(result.getResponse().getContentAsString(), "$.data.pointBalance"))
					.isEqualTo(200);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EXPIRE'", Integer.class,
				member.memberId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(amount), 0)::bigint FROM point_transactions WHERE member_id = ?", Long.class,
				member.memberId())).isEqualTo(200L);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT MIN(balance_after)::bigint
				FROM (
				    SELECT SUM(amount) OVER (ORDER BY occurred_at, id) AS balance_after
				    FROM point_transactions
				    WHERE member_id = ?
				) balances
				""", Long.class, member.memberId())).isGreaterThanOrEqualTo(0L);
	}

	/** 미션 제출이 실패하면 같은 transaction에서 선반영한 만료도 함께 롤백한다. */
	@Test
	@DisplayName("미션 제출 실패는 선반영한 만료와 미션 변경을 함께 롤백한다")
	void failedMissionSubmissionRollsBackExpiration() throws Exception {
		insertPointTransaction(member.memberId(), 300, Instant.now().minus(241, ChronoUnit.HOURS));
		UUID settledTripId = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(settledTripId, 200, Instant.now().minus(1, ChronoUnit.MINUTES));
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("먼 만료 보상 장소", 30.1);
		UUID missionId = insertOxMission(place, "실패하는 만료 보상 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "rollback-expiration", oxRequest(tripId, true)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("LOCATION_VERIFICATION_FAILED"));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EXPIRE'", Integer.class,
				member.memberId())).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT expired_at IS NULL FROM point_settlements WHERE trip_id = ?",
				Boolean.class, settledTripId)).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mission_participations WHERE trip_id = ?",
				Integer.class, tripId)).isZero();
	}

	/** 탈퇴가 먼저 확정된 회원은 기존 토큰으로 미션 보상을 요청할 수 없다. */
	@Test
	@DisplayName("탈퇴가 먼저 확정되면 미션 보상을 거부하고 WITHDRAWN을 유지한다")
	void rejectsMissionRewardAfterWithdrawalCompleted() throws Exception {
		jdbcTemplate.update("""
				UPDATE members
				SET status = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP,
				    purge_after = CURRENT_TIMESTAMP + INTERVAL '30 days'
				WHERE id = ?
				""", member.memberId());

		mockMvc.perform(submit(UUID.randomUUID(), "withdrawn-reward", oxRequest(UUID.randomUUID(), true)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));

		assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM members WHERE id = ?", String.class,
				member.memberId())).isEqualTo("WITHDRAWN");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM point_transactions WHERE member_id = ?",
				Integer.class, member.memberId())).isZero();
	}

	/** 객관식 미션에 정답을 제출하면 완료 처리된다. */
	@Test
	@DisplayName("객관식 미션에 정답을 제출하면 완료된다")
	void correctMultipleChoiceSubmissionCompletesMission() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("객관식 장소", 20);
		UUID missionId = insertMultipleChoiceMission(place, "객관식 미션", 100, null);
		UUID correctChoice = insertChoice(missionId, "정답", true, 0);
		insertChoice(missionId, "오답", false, 1);

		mockMvc.perform(submit(missionId, "submit-mc-correct", choiceRequest(tripId, correctChoice)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.completed").value(true))
				.andExpect(jsonPath("$.data.rewardPoints").value(100));
	}

	/** 일반 퀴즈에 오답을 제출하면 AVAILABLE을 유지하며 재도전할 수 있다. */
	@Test
	@DisplayName("일반 퀴즈 오답은 AVAILABLE을 유지하고 재도전할 수 있다")
	void incorrectAnswerToNormalQuizKeepsAvailableAndAllowsRetry() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("OX 장소", 20);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "submit-ox-wrong", oxRequest(tripId, false))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.completed").value(false))
				.andExpect(jsonPath("$.data.remainingAttempts").isEmpty())
				.andExpect(jsonPath("$.data.rewardPoints").value(0));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM mission_participations WHERE trip_id = ? AND mission_id = ?", String.class, tripId,
				missionId)).isEqualTo("AVAILABLE");

		mockMvc.perform(submit(missionId, "submit-ox-retry", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.completed").value(true));
	}

	/** 스페셜 퀴즈는 도전 기회를 모두 소진하면 EXHAUSTED로 전이하고 이후 제출은 409를 받는다. */
	@Test
	@DisplayName("스페셜 퀴즈는 기회를 모두 소진하면 EXHAUSTED로 전이하고 재제출은 409를 받는다")
	void specialQuizExhaustsAttemptsAndRejectsFurtherSubmissions() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("스페셜 장소", 20);
		UUID missionId = insertOxMission(place, "스페셜 퀴즈", 100, 2, true);

		mockMvc.perform(submit(missionId, "special-attempt-1", oxRequest(tripId, false))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.completed").value(false))
				.andExpect(jsonPath("$.data.remainingAttempts").value(1));

		mockMvc.perform(submit(missionId, "special-attempt-2", oxRequest(tripId, false))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.completed").value(false))
				.andExpect(jsonPath("$.data.remainingAttempts").value(0));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM mission_participations WHERE trip_id = ? AND mission_id = ?", String.class, tripId,
				missionId)).isEqualTo("EXHAUSTED");

		mockMvc.perform(submit(missionId, "special-attempt-3", oxRequest(tripId, true)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** 완료된 미션에 다시 제출하면 409를 받는다. */
	@Test
	@DisplayName("완료된 미션에 재제출하면 409를 받는다")
	void resubmittingCompletedMissionReturns409() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("OX 장소", 20);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);
		mockMvc.perform(submit(missionId, "first-complete", oxRequest(tripId, true))).andExpect(status().isOk());

		mockMvc.perform(submit(missionId, "second-attempt", oxRequest(tripId, true))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** 같은 여행에서 같은 문화재에 연결된 다른 미션을 완료해도 방문 기록은 늘지 않는다. */
	@Test
	@DisplayName("같은 문화재의 다른 미션을 완료해도 방문 기록은 한 번만 생성된다")
	void completingAnotherMissionForSamePlaceDoesNotDuplicateVisit() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("공통 장소", 20);
		UUID firstMission = insertOxMission(place, "미션1", 100, null, true);
		UUID secondMission = insertOxMission(place, "미션2", 100, null, true);

		mockMvc.perform(submit(firstMission, "visit-first", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.visitRecorded").value(true));

		mockMvc.perform(submit(secondMission, "visit-second", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.visitRecorded").value(false))
				.andExpect(jsonPath("$.data.visitId").isEmpty());

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM visit_records WHERE trip_id = ? AND place_id = ?",
				Integer.class, tripId, place)).isEqualTo(1);
	}

	/** 반올림 전 실제 거리가 30m를 초과하면 403을 받고 어떤 상태도 바뀌지 않는다. */
	@Test
	@DisplayName("30m를 초과하면 403을 받고 상태를 바꾸지 않는다")
	void returns403WhenBeyondParticipationRadiusAndChangesNothing() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("먼 장소", 30.1);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "too-far-key", oxRequest(tripId, true))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("LOCATION_VERIFICATION_FAILED"));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mission_participations WHERE trip_id = ?",
				Integer.class, tripId)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM point_transactions WHERE member_id = ?",
				Integer.class, member.memberId())).isZero();
	}

	/** 30m 경계는 참여를 허용한다. */
	@Test
	@DisplayName("30m 경계는 참여를 허용한다")
	void allowsSubmissionExactlyAtParticipationBoundary() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("경계 장소", 30.0);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "boundary", oxRequest(tripId, true))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.completed").value(true));
	}

	/** 존재하지 않거나 본인 소유가 아닌 여행은 404를 받는다. */
	@Test
	@DisplayName("본인 소유가 아니거나 존재하지 않는 여행은 404를 받는다")
	void returns404WhenTripIsNotOwnedOrMissing() throws Exception {
		UUID place = insertProjectedPlace("장소", 20);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);

		mockMvc.perform(submit(missionId, "missing-trip", oxRequest(UUID.randomUUID(), true)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 진행 중이 아닌 본인 여행은 409를 받는다. */
	@Test
	@DisplayName("진행 중이 아닌 본인 여행은 409를 받는다")
	void returns409WhenOwnTripIsNotInProgress() throws Exception {
		UUID place = insertProjectedPlace("장소", 20);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);
		UUID endedTripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status, ended_at) VALUES (?, ?, 'ENDED', now())",
				endedTripId, member.memberId());

		mockMvc.perform(submit(missionId, "ended-trip", oxRequest(endedTripId, true))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("TRIP_NOT_IN_PROGRESS"));
	}

	/** 존재하지 않는 미션은 404를 받는다. */
	@Test
	@DisplayName("존재하지 않는 미션에 제출하면 404를 받는다")
	void returns404WhenMissionDoesNotExist() throws Exception {
		UUID tripId = startTrip(member.memberId());

		mockMvc.perform(submit(UUID.randomUUID(), "missing-mission", oxRequest(tripId, true)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 같은 키와 같은 본문의 재요청은 최초 성공 응답을 재사용하고 부작용을 반복하지 않는다. */
	@Test
	@DisplayName("동일한 멱등성 요청은 최초 응답을 재사용하고 부작용을 반복하지 않는다")
	void sameIdempotentRequestReplaysFirstResponseWithoutSideEffects() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("OX 장소", 20);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);
		String key = "retry-submit-key";
		String body = oxRequest(tripId, true);

		String first = mockMvc.perform(submit(missionId, key, body)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String retried = mockMvc.perform(submit(missionId, key, body)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

		assertThat(retried).isEqualTo(first);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EARN'", Integer.class,
				member.memberId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM visit_records WHERE trip_id = ?", Integer.class,
				tripId)).isEqualTo(1);
	}

	/** 같은 키를 다른 요청 본문에 재사용하면 409를 받는다. */
	@Test
	@DisplayName("멱등성 키를 다른 본문에 재사용하면 409를 받는다")
	void reusedKeyWithDifferentBodyConflicts() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("OX 장소", 20);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);
		String key = "conflict-submit-key";

		mockMvc.perform(submit(missionId, key, oxRequest(tripId, true))).andExpect(status().isOk());
		mockMvc.perform(submit(missionId, key, oxRequest(tripId, false))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	/** 같은 미션에 동시에 여러 완료 요청이 들어와도 완료·보상·방문 기록은 정확히 한 번만 확정된다. */
	@Test
	@DisplayName("동시 완료 요청은 완료·보상·방문 기록을 한 번만 확정한다")
	void concurrentSubmissionsCompleteMissionExactlyOnce() throws Exception {
		UUID tripId = startTrip(member.memberId());
		UUID place = insertProjectedPlace("동시성 장소", 20);
		UUID missionId = insertOxMission(place, "OX 미션", 100, null, true);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor
					.submit(() -> concurrentSubmit(missionId, "concurrent-a", oxRequest(tripId, true), ready, start));
			Future<MvcResult> second = executor
					.submit(() -> concurrentSubmit(missionId, "concurrent-b", oxRequest(tripId, true), ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
			int[] statuses = {firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()};
			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM mission_participations WHERE trip_id = ? AND status = 'COMPLETED'", Integer.class,
				tripId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EARN'", Integer.class,
				member.memberId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM visit_records WHERE trip_id = ?", Integer.class,
				tripId)).isEqualTo(1);
	}

	/** 인증되지 않은 요청은 401을 받는다. */
	@Test
	@DisplayName("인증하지 않으면 401을 받는다")
	void returns401WhenUnauthenticated() throws Exception {
		mockMvc.perform(
				post("/missions/{missionId}/submissions", UUID.randomUUID()).header("Idempotency-Key", "unauth-key")
						.contentType(MediaType.APPLICATION_JSON).content(oxRequest(UUID.randomUUID(), true)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private MvcResult concurrentSubmit(UUID missionId, String key, String body, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return mockMvc.perform(submit(missionId, key, body)).andReturn();
	}

	private MockHttpServletRequestBuilder submit(UUID missionId, String idempotencyKey, String body) {
		var request = post("/missions/{missionId}/submissions", missionId)
				.header("Authorization", "Bearer " + member.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content(body);
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey);
		}
		return request;
	}

	private String oxRequest(UUID tripId, boolean oxAnswer) {
		return "{\"tripId\":\"" + tripId + "\",\"type\":\"OX\",\"oxAnswer\":" + oxAnswer + ",\"location\":"
				+ locationJson() + "}";
	}

	private String choiceRequest(UUID tripId, UUID choiceId) {
		return "{\"tripId\":\"" + tripId + "\",\"type\":\"MULTIPLE_CHOICE\",\"choiceId\":\"" + choiceId
				+ "\",\"location\":" + locationJson() + "}";
	}

	private String locationJson() {
		return "{\"latitude\":" + ORIGIN_LATITUDE + ",\"longitude\":" + ORIGIN_LONGITUDE
				+ ",\"accuracyMeters\":5.5,\"capturedAt\":\"2026-08-12T15:30:00+09:00\"}";
	}

	private AuthenticatedMember insertAuthenticatedMember() {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
		return new AuthenticatedMember(memberId, accessTokenService.issue(memberId, sessionId));
	}

	private UUID startTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status) VALUES (?, ?, 'IN_PROGRESS')", tripId, memberId);
		return tripId;
	}

	/** 만료 선반영 fixture의 기존 잔액을 만드는 EARN 내역을 저장한다. */
	private void insertPointTransaction(UUID memberId, long amount, Instant occurredAt) {
		jdbcTemplate.update("""
				INSERT INTO point_transactions (id, member_id, type, amount, description, occurred_at)
				VALUES (?, ?, 'EARN', ?, '기존 미션 보상', ?)
				""", UUID.randomUUID(), memberId, amount, Timestamp.from(occurredAt));
	}

	/** 만료 정산 fixture가 참조할 회원 소유의 정산 완료 여행을 만든다. */
	private UUID insertSettledTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO trips (id, member_id, status, ended_at, settled_at) VALUES (?, ?, 'SETTLED', now(), now())",
				tripId, memberId);
		return tripId;
	}

	/** 지정한 시각에 만료되는 CARRY_OVER 정산 fixture를 만든다. */
	private void insertCarryOverSettlement(UUID tripId, long settledPoints, Instant expiresAt) {
		jdbcTemplate.update("""
				INSERT INTO point_settlements (id, trip_id, choice, settled_points, expires_at, settled_at)
				VALUES (?, ?, 'CARRY_OVER', ?, ?, ?)
				""", UUID.randomUUID(), tripId, settledPoints, Timestamp.from(expiresAt),
				Timestamp.from(expiresAt.minus(240, ChronoUnit.HOURS)));
	}

	private UUID insertPlace(String name, double latitude, double longitude) {
		UUID id = UUID.randomUUID();
		jdbcTemplate
				.update("INSERT INTO places (id, category, name, location) VALUES (?, 'HERITAGE'::place_category, ?, "
						+ "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)", id, name, longitude, latitude);
		return id;
	}

	/**
	 * 원점에서 지정한 거리(m)만큼 떨어진 지점에 장소를 만든다. 삽입과 조회가 같은 PostGIS geodesic 계산을 쓰므로 왕복 오차가
	 * 없다.
	 */
	private UUID insertProjectedPlace(String name, double distanceMeters) {
		Map<String, Object> point = jdbcTemplate.queryForMap(
				"SELECT ST_Y(pt::geometry) AS lat, ST_X(pt::geometry) AS lon FROM "
						+ "(SELECT ST_Project(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, 0) AS pt) t",
				ORIGIN_LONGITUDE, ORIGIN_LATITUDE, distanceMeters);
		return insertPlace(name, ((Number) point.get("lat")).doubleValue(), ((Number) point.get("lon")).doubleValue());
	}

	private UUID insertOxMission(UUID placeId, String title, int rewardPoints, Integer maxAttempts,
			boolean correctAnswer) {
		return insertMission(placeId, "OX", title, rewardPoints, maxAttempts, correctAnswer);
	}

	private UUID insertMultipleChoiceMission(UUID placeId, String title, int rewardPoints, Integer maxAttempts) {
		return insertMission(placeId, "MULTIPLE_CHOICE", title, rewardPoints, maxAttempts, null);
	}

	private UUID insertMission(UUID placeId, String type, String title, int rewardPoints, Integer maxAttempts,
			Boolean oxCorrectAnswer) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO missions (id, place_id, location, type, title, description, reward_points, "
						+ "max_attempts, ox_correct_answer) VALUES (?, ?, (SELECT location FROM places WHERE id = ?), "
						+ "?::mission_type, ?, '설명', ?, ?, ?)",
				id, placeId, placeId, type, title, rewardPoints, maxAttempts, oxCorrectAnswer);
		return id;
	}

	private UUID insertChoice(UUID missionId, String label, boolean correct, int sortOrder) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO mission_choices (id, mission_id, label, correct, sort_order) VALUES (?, ?, ?, ?, ?)", id,
				missionId, label, correct, sortOrder);
		return id;
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> APPLICATION_USERNAME);
		registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
