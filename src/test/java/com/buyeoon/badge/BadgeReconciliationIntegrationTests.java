package com.buyeoon.badge;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** UC-14 과거 활동 배지 reconciliation 흐름의 통합 테스트다. */
@SpringBootTest
@Testcontainers
class BadgeReconciliationIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	// 시드 마이그레이션이 부여 지역에 장소·미션 예시 데이터를 채워두므로, 격리를 위해 남극 인근 좌표를 기준으로
	// 테스트 데이터를 배치한다.
	private static final double ORIGIN_LATITUDE = -75.0;
	private static final double ORIGIN_LONGITUDE = 0.0;

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private BadgeReconciliationService reconciliationService;

	@Autowired
	private BadgeEvaluationService badgeEvaluationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM member_badges");
		jdbcTemplate.update("DELETE FROM badge_conditions");
		jdbcTemplate.update("DELETE FROM badges");
		jdbcTemplate.update("DELETE FROM point_settlements");
		jdbcTemplate.update("DELETE FROM visit_records");
		jdbcTemplate.update("DELETE FROM mission_participations");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_member_badges_insert ON member_badges");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_member_badges_insert()");
		jdbcTemplate.update(
				"DELETE FROM missions WHERE place_id IN (SELECT id FROM places WHERE ST_Y(location::geometry) < -70)");
		jdbcTemplate.update("DELETE FROM places WHERE ST_Y(location::geometry) < -70");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("과거 미션·문화재·양수 포인트 기부 활동으로 조건을 충족한 active member는 배지와 알림을 받는다")
	void reconcilesPastActivityAcrossAllThreeMetrics() {
		UUID memberId = insertActiveMember();
		UUID missionBadgeId = insertBadge("탐험가");
		insertCondition(missionBadgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID heritageBadgeId = insertBadge("문화탐방가");
		insertCondition(heritageBadgeId, "HERITAGE_VISITED_COUNT", 1);
		UUID donationBadgeId = insertBadge("기부천사");
		insertCondition(donationBadgeId, "POINT_DONATION_COUNT", 1);

		UUID missionTrip = insertTrip(memberId);
		UUID place = insertPlace("장소A");
		UUID mission = insertMission(place);
		insertCompletedParticipation(missionTrip, mission, Instant.now().minus(3, ChronoUnit.DAYS));

		UUID heritageTrip = insertTrip(memberId);
		insertVisitRecord(heritageTrip, mission, place, Instant.now().minus(2, ChronoUnit.DAYS));

		UUID donationTrip = insertTrip(memberId);
		insertSettlement(donationTrip, "LEAVE_TO_BUYEO", 100, Instant.now().minus(1, ChronoUnit.DAYS));

		Instant before = Instant.now();
		reconciliationService.reconcile();
		Instant after = Instant.now();

		assertThat(memberBadgeRow(memberId, missionBadgeId).get("trip_id")).isEqualTo(missionTrip);
		assertThat(memberBadgeRow(memberId, heritageBadgeId).get("trip_id")).isEqualTo(heritageTrip);
		assertThat(memberBadgeRow(memberId, donationBadgeId).get("trip_id")).isEqualTo(donationTrip);
		for (UUID badgeId : List.of(missionBadgeId, heritageBadgeId, donationBadgeId)) {
			Instant earnedAt = ((Timestamp) memberBadgeRow(memberId, badgeId).get("earned_at")).toInstant();
			assertThat(earnedAt).isBetween(before, after);
			assertThat(notificationCount(memberId, badgeId)).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("CARRY_OVER와 NO_POINTS 정산은 포인트 기부 조건에 포함되지 않는다")
	void excludesCarryOverAndNoPointsSettlements() {
		UUID memberId = insertActiveMember();
		UUID badgeId = insertBadge("기부천사");
		insertCondition(badgeId, "POINT_DONATION_COUNT", 1);

		UUID carryOverTrip = insertTrip(memberId);
		insertSettlement(carryOverTrip, "CARRY_OVER", 100, Instant.now().minus(2, ChronoUnit.DAYS));
		UUID zeroTrip = insertTrip(memberId);
		insertSettlement(zeroTrip, "NO_POINTS", 0, Instant.now().minus(1, ChronoUnit.DAYS));

		reconciliationService.reconcile();

		assertThat(memberBadgeCount(memberId, badgeId)).isZero();
	}

	@Test
	@DisplayName("복합 조건 배지는 여러 metric에 기여한 활동 중 가장 최근 활동의 여행에 연결한다")
	void compositeBadgeConnectsToLatestContributingTripAcrossMetrics() {
		UUID memberId = insertActiveMember();
		UUID badgeId = insertBadge("탐험 기부가");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		insertCondition(badgeId, "POINT_DONATION_COUNT", 1);

		UUID missionTrip = insertTrip(memberId);
		UUID place = insertPlace("장소A");
		UUID mission = insertMission(place);
		insertCompletedParticipation(missionTrip, mission, Instant.now().minus(5, ChronoUnit.DAYS));

		UUID donationTrip = insertTrip(memberId);
		insertSettlement(donationTrip, "LEAVE_TO_BUYEO", 50, Instant.now().minus(1, ChronoUnit.DAYS));

		reconciliationService.reconcile();

		assertThat(memberBadgeRow(memberId, badgeId).get("trip_id")).isEqualTo(donationTrip);
	}

	@Test
	@DisplayName("고유 문화재 수는 각 문화재의 최초 방문만 세고, 재방문은 획득 여행에 영향을 주지 않는다")
	void heritageMetricCountsOnlyFirstVisitPerPlace() {
		UUID memberId = insertActiveMember();
		UUID badgeId = insertBadge("문화탐방가");
		insertCondition(badgeId, "HERITAGE_VISITED_COUNT", 2);

		UUID placeA = insertPlace("장소A");
		UUID missionA = insertMission(placeA);
		UUID placeB = insertPlace("장소B");
		UUID missionB = insertMission(placeB);

		UUID firstVisitTripA = insertTrip(memberId);
		insertVisitRecord(firstVisitTripA, missionA, placeA, Instant.now().minus(5, ChronoUnit.DAYS));
		UUID firstVisitTripB = insertTrip(memberId);
		insertVisitRecord(firstVisitTripB, missionB, placeB, Instant.now().minus(3, ChronoUnit.DAYS));
		UUID revisitTrip = insertTrip(memberId);
		insertVisitRecord(revisitTrip, missionA, placeA, Instant.now().minus(1, ChronoUnit.DAYS));

		reconciliationService.reconcile();

		assertThat(memberBadgeRow(memberId, badgeId).get("trip_id")).isEqualTo(firstVisitTripB);
	}

	@Test
	@DisplayName("탈퇴한 회원은 member row lock 후 상태 재확인에서 걸러져 배지와 알림을 받지 않는다")
	void skipsWithdrawnMemberAfterLockingMemberRow() {
		UUID memberId = insertWithdrawnMember();
		UUID badgeId = insertBadge("탐험가");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID tripId = insertTrip(memberId);
		UUID place = insertPlace("장소A");
		UUID mission = insertMission(place);
		insertCompletedParticipation(tripId, mission, Instant.now().minus(1, ChronoUnit.DAYS));

		reconciliationService.reconcile();

		assertThat(memberBadgeCount(memberId, badgeId)).isZero();
		assertThat(notificationCount(memberId, badgeId)).isZero();
	}

	@Test
	@DisplayName("이미 획득했거나 지급이 중단된 배지는 reconciliation에서 다시 지급하지 않는다")
	void skipsAlreadyEarnedAndRetiredBadges() {
		UUID memberId = insertActiveMember();
		UUID tripId = insertTrip(memberId);
		UUID place = insertPlace("장소A");
		UUID mission = insertMission(place);
		insertCompletedParticipation(tripId, mission, Instant.now().minus(1, ChronoUnit.DAYS));

		UUID earnedBadgeId = insertBadge("탐험가");
		insertCondition(earnedBadgeId, "MISSION_COMPLETED_COUNT", 1);
		jdbcTemplate.update("INSERT INTO member_badges (member_id, badge_id, trip_id) VALUES (?, ?, ?)", memberId,
				earnedBadgeId, tripId);

		UUID retiredBadgeId = insertBadge("은퇴한 배지");
		insertCondition(retiredBadgeId, "MISSION_COMPLETED_COUNT", 1);
		jdbcTemplate.update("UPDATE badges SET retired_at = now() WHERE id = ?", retiredBadgeId);

		reconciliationService.reconcile();

		assertThat(memberBadgeCount(memberId, earnedBadgeId)).isEqualTo(1);
		assertThat(notificationCount(memberId, earnedBadgeId)).isZero();
		assertThat(memberBadgeCount(memberId, retiredBadgeId)).isZero();
	}

	@Test
	@DisplayName("한 회원의 저장 실패는 그 회원만 rollback하고 다른 회원 처리를 막지 않으며 다음 실행에서 재판정한다")
	void isolatesOneMemberFailureAndRetriesOnNextRun() {
		UUID failingMemberId = insertActiveMember();
		UUID healthyMemberId = insertActiveMember();
		UUID badgeId = insertBadge("탐험가");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID place = insertPlace("장소A");
		UUID mission = insertMission(place);

		UUID failingTrip = insertTrip(failingMemberId);
		insertCompletedParticipation(failingTrip, mission, Instant.now().minus(1, ChronoUnit.DAYS));
		UUID healthyTrip = insertTrip(healthyMemberId);
		insertCompletedParticipation(healthyTrip, mission, Instant.now().minus(1, ChronoUnit.DAYS));

		jdbcTemplate.execute("CREATE FUNCTION fail_member_badges_insert() RETURNS trigger AS $$ " + "BEGIN "
				+ "    IF NEW.member_id = '" + failingMemberId + "' THEN "
				+ "        RAISE EXCEPTION 'forced reconciliation failure'; " + "    END IF; " + "    RETURN NEW; "
				+ "END; " + "$$ LANGUAGE plpgsql");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_member_badges_insert
				BEFORE INSERT ON member_badges
				FOR EACH ROW EXECUTE FUNCTION fail_member_badges_insert()
				""");

		reconciliationService.reconcile();

		assertThat(memberBadgeCount(failingMemberId, badgeId)).isZero();
		assertThat(memberBadgeCount(healthyMemberId, badgeId)).isEqualTo(1);

		jdbcTemplate.execute("DROP TRIGGER fail_member_badges_insert ON member_badges");
		jdbcTemplate.execute("DROP FUNCTION fail_member_badges_insert()");

		reconciliationService.reconcile();

		assertThat(memberBadgeCount(failingMemberId, badgeId)).isEqualTo(1);
	}

	@Test
	@DisplayName("실시간 판정과 reconciliation이 동시에 경합해도 획득 이력과 알림은 한 번만 생성된다")
	void realtimeAndReconciliationRaceProduceExactlyOneAwardAndNotification() throws Exception {
		UUID memberId = insertActiveMember();
		UUID badgeId = insertBadge("탐험가");
		insertCondition(badgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID place = insertPlace("장소A");
		UUID mission = insertMission(place);
		UUID tripId = insertTrip(memberId);
		insertCompletedParticipation(tripId, mission, Instant.now().minus(1, ChronoUnit.DAYS));

		Set<BadgeMetric> affectedMetrics = EnumSet.of(BadgeMetric.MISSION_COMPLETED_COUNT);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> realtime = executor.submit(concurrentTask(ready, start,
					() -> badgeEvaluationService.award(memberId, tripId, affectedMetrics)));
			Future<?> reconciliation = executor.submit(concurrentTask(ready, start, reconciliationService::reconcile));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			realtime.get(10, TimeUnit.SECONDS);
			reconciliation.get(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		assertThat(memberBadgeCount(memberId, badgeId)).isEqualTo(1);
		assertThat(notificationCount(memberId, badgeId)).isEqualTo(1);
	}

	private <T> Callable<T> concurrentTask(CountDownLatch ready, CountDownLatch start, Callable<T> task) {
		return () -> {
			ready.countDown();
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시 실행 시작 신호를 받지 못했습니다.");
			}
			return task.call();
		};
	}

	private UUID insertActiveMember() {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		return memberId;
	}

	private UUID insertWithdrawnMember() {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO members (id, status, withdrawn_at, purge_after)
				VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days')
				""", memberId);
		return memberId;
	}

	private UUID insertTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO trips (id, member_id, status, ended_at) VALUES (?, ?, 'ENDED', CURRENT_TIMESTAMP)", tripId,
				memberId);
		return tripId;
	}

	private UUID insertPlace(String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO places (id, category, name, location) VALUES (?, 'HERITAGE'::place_category, ?, "
						+ "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)",
				id, name, ORIGIN_LONGITUDE, ORIGIN_LATITUDE);
		return id;
	}

	private UUID insertMission(UUID placeId) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO missions (id, place_id, type, title, description, reward_points)
				VALUES (?, ?, 'PHOTO', '테스트 미션', '설명', 100)
				""", id, placeId);
		return id;
	}

	private void insertCompletedParticipation(UUID tripId, UUID missionId, Instant completedAt) {
		jdbcTemplate.update("""
				INSERT INTO mission_participations (trip_id, mission_id, status, attempt_count, completed_at)
				VALUES (?, ?, 'COMPLETED', 1, ?)
				""", tripId, missionId, Timestamp.from(completedAt));
	}

	private void insertVisitRecord(UUID tripId, UUID missionId, UUID placeId, Instant visitedAt) {
		jdbcTemplate.update("INSERT INTO visit_records (trip_id, mission_id, place_id, visited_at) VALUES (?, ?, ?, ?)",
				tripId, missionId, placeId, Timestamp.from(visitedAt));
	}

	private void insertSettlement(UUID tripId, String choice, long settledPoints, Instant settledAt) {
		Timestamp settledAtTimestamp = Timestamp.from(settledAt);
		Timestamp expiresAt = "CARRY_OVER".equals(choice)
				? Timestamp.from(settledAt.plus(240, ChronoUnit.HOURS))
				: null;
		jdbcTemplate.update("""
				INSERT INTO point_settlements (trip_id, choice, settled_points, expires_at, settled_at)
				VALUES (?, ?::settlement_choice, ?, ?, ?)
				""", tripId, choice, settledPoints, expiresAt, settledAtTimestamp);
	}

	private UUID insertBadge(String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO badges (id, category, name, description, condition_text) "
				+ "VALUES (?, 'EXPLORATION'::badge_category, ?, '설명', '조건')", id, name);
		return id;
	}

	private void insertCondition(UUID badgeId, String metricKey, long threshold) {
		jdbcTemplate.update("INSERT INTO badge_conditions (badge_id, metric_key, threshold) VALUES (?, ?, ?)", badgeId,
				metricKey, threshold);
	}

	private Map<String, Object> memberBadgeRow(UUID memberId, UUID badgeId) {
		return jdbcTemplate.queryForMap("SELECT * FROM member_badges WHERE member_id = ? AND badge_id = ?", memberId,
				badgeId);
	}

	private long memberBadgeCount(UUID memberId, UUID badgeId) {
		return Objects.requireNonNull(
				jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE member_id = ? AND badge_id = ?",
						Long.class, memberId, badgeId));
	}

	private long notificationCount(UUID memberId, UUID badgeId) {
		return Objects.requireNonNull(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? "
						+ "AND type = 'BADGE' AND target_type = 'BADGE' AND target_id = ?",
				Long.class, memberId, badgeId));
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
}
