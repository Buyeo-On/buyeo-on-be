package com.buyeoon.badge;

import static org.assertj.core.api.Assertions.assertThat;

import com.buyeoon.badge.BadgeEvaluationService.AwardedBadgeResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

/** #190 전체 배지 달성률 meta 배지("부여 마스터")의 실시간 지급 통합 테스트다. */
@SpringBootTest
@Testcontainers
class BadgeAcquiredCountIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

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
		jdbcTemplate.update("DELETE FROM mission_participations");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("마지막 배지를 획득해 100%를 달성하면 같은 activity에서 전체 배지 달성률 배지도 함께 지급된다")
	void awardsMetaBadgeWhenLastOtherBadgeIsEarnedInSameActivity() {
		UUID memberId = insertActiveMember();
		UUID missionBadgeId = insertBadge("탐험가");
		insertCondition(missionBadgeId, "MISSION_COMPLETED_COUNT", 1);
		UUID donationBadgeId = insertBadge("기부천사");
		insertCondition(donationBadgeId, "POINT_DONATION_COUNT", 1);
		UUID metaBadgeId = insertBadge("부여 마스터");
		insertCondition(metaBadgeId, "BADGE_ACQUIRED_COUNT", 2);

		UUID place = insertPlace("장소A");
		UUID mission = insertMission(place);
		UUID missionTrip = insertTrip(memberId);
		insertCompletedParticipation(missionTrip, mission, Instant.now().minus(2, ChronoUnit.DAYS));

		List<AwardedBadgeResult> firstAward = badgeEvaluationService.award(memberId, missionTrip,
				EnumSet.of(BadgeMetric.MISSION_COMPLETED_COUNT));

		assertThat(firstAward).extracting(AwardedBadgeResult::badgeId).containsExactly(missionBadgeId);
		assertThat(memberBadgeCount(memberId, metaBadgeId)).isZero();

		UUID donationTrip = insertTrip(memberId);
		insertSettlement(donationTrip, 50, Instant.now().minus(1, ChronoUnit.DAYS));

		List<AwardedBadgeResult> secondAward = badgeEvaluationService.award(memberId, donationTrip,
				EnumSet.of(BadgeMetric.POINT_DONATION_COUNT));

		// V17·V18 seed migration이 등록한 실제 '사비의 마음'·'부여 마스터' 배지도 같은 활동으로 함께 지급될 수 있어
		// 부분집합만 검증한다.
		assertThat(secondAward).extracting(AwardedBadgeResult::badgeId).contains(donationBadgeId, metaBadgeId);
		assertThat(memberBadgeRow(memberId, metaBadgeId).get("trip_id")).isEqualTo(donationTrip);
		assertThat(notificationCount(memberId, metaBadgeId)).isEqualTo(1);
	}

	private UUID insertActiveMember() {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
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
		jdbcTemplate
				.update("INSERT INTO places (id, category, name, location) VALUES (?, 'HERITAGE'::place_category, ?, "
						+ "ST_SetSRID(ST_MakePoint(0, -75), 4326)::geography)", id, name);
		return id;
	}

	private UUID insertMission(UUID placeId) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO missions (id, place_id, location, type, title, description, reward_points)
				VALUES (?, ?, (SELECT location FROM places WHERE id = ?), 'PHOTO', '테스트 미션', '설명', 100)
				""", id, placeId, placeId);
		return id;
	}

	private void insertCompletedParticipation(UUID tripId, UUID missionId, Instant completedAt) {
		jdbcTemplate.update("""
				INSERT INTO mission_participations (trip_id, mission_id, status, attempt_count, completed_at)
				VALUES (?, ?, 'COMPLETED', 1, ?)
				""", tripId, missionId, Timestamp.from(completedAt));
	}

	private void insertSettlement(UUID tripId, long settledPoints, Instant settledAt) {
		jdbcTemplate.update("""
				INSERT INTO point_settlements (trip_id, choice, settled_points, settled_at)
				VALUES (?, 'LEAVE_TO_BUYEO'::settlement_choice, ?, ?)
				""", tripId, settledPoints, Timestamp.from(settledAt));
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
