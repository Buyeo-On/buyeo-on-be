package com.buyeoon.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buyeoon.point.application.PointExpirationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

/** UC-24 이월 포인트 만료 확정 흐름의 통합 테스트다. */
@SpringBootTest
@Testcontainers
class PointExpirationIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private PointExpirationService expirationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_point_transactions_insert ON point_transactions");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_point_transactions_insert()");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM point_settlements");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("만료 시각이 도래한 CARRY_OVER 정산은 약속된 만료 시각의 EXPIRE 내역과 실제 처리 시각을 정확히 한 번 기록한다")
	void expiresDueCarryOverExactlyOnce() {
		UUID memberId = insertActiveMember();
		UUID tripId = insertTrip(memberId);
		Instant expiresAt = Instant.now().minusSeconds(60);
		insertCarryOverSettlement(tripId, 300, expiresAt.minus(240, ChronoUnit.HOURS));

		Instant before = currentDbTime();
		int expired = expirationService.expireDueSettlements(memberId);
		Instant after = currentDbTime();

		assertThat(expired).isEqualTo(1);
		Map<String, Object> transaction = onlyExpireTransaction(memberId);
		assertThat(transaction.get("amount")).isEqualTo(-300L);
		assertThat(((Timestamp) transaction.get("occurred_at")).toInstant()).isEqualTo(expiresAt);

		Map<String, Object> settlement = settlementRow(tripId);
		Instant expiredAt = ((Timestamp) settlement.get("expired_at")).toInstant();
		assertThat(expiredAt).isBetween(before, after);
	}

	@Test
	@DisplayName("아직 만료되지 않은 이월 정산은 변경하지 않는다")
	void doesNotTouchNotYetDueCarryOver() {
		UUID memberId = insertActiveMember();
		UUID tripId = insertTrip(memberId);
		insertCarryOverSettlement(tripId, 100, Instant.now().minus(1, ChronoUnit.HOURS));

		int expired = expirationService.expireDueSettlements(memberId);

		assertThat(expired).isZero();
		assertThat(settlementRow(tripId).get("expired_at")).isNull();
		assertThat(expireTransactionCount(memberId)).isZero();
	}

	@Test
	@DisplayName("이미 만료 처리된 정산은 다시 처리하지 않는다")
	void doesNotReprocessAlreadyExpiredSettlement() {
		UUID memberId = insertActiveMember();
		UUID tripId = insertTrip(memberId);
		Instant expiresAt = Instant.now().minus(2, ChronoUnit.HOURS);
		insertCarryOverSettlement(tripId, 150, expiresAt.minus(240, ChronoUnit.HOURS));

		assertThat(expirationService.expireDueSettlements(memberId)).isEqualTo(1);
		Instant firstExpiredAt = ((Timestamp) settlementRow(tripId).get("expired_at")).toInstant();

		assertThat(expirationService.expireDueSettlements(memberId)).isZero();
		assertThat(expireTransactionCount(memberId)).isEqualTo(1);
		assertThat(((Timestamp) settlementRow(tripId).get("expired_at")).toInstant()).isEqualTo(firstExpiredAt);
	}

	@Test
	@DisplayName("탈퇴 회원은 새 EXPIRE 내역을 만들지 않는다")
	void skipsWithdrawnMember() {
		UUID memberId = insertWithdrawnMember();
		UUID tripId = insertTrip(memberId);
		insertCarryOverSettlement(tripId, 100, Instant.now().minus(241, ChronoUnit.HOURS));

		int expired = expirationService.expireDueSettlements(memberId);

		assertThat(expired).isZero();
		assertThat(settlementRow(tripId).get("expired_at")).isNull();
	}

	@Test
	@DisplayName("한 회원의 만료 대상 정산이 여러 건이면 회원별 호출 한 번으로 모두 확정한다")
	void expiresAllDueSettlementsForOneMemberInOneCall() {
		UUID memberId = insertActiveMember();
		UUID firstTrip = insertTrip(memberId);
		UUID secondTrip = insertTrip(memberId);
		insertCarryOverSettlement(firstTrip, 100, Instant.now().minus(241, ChronoUnit.HOURS));
		insertCarryOverSettlement(secondTrip, 200, Instant.now().minus(300, ChronoUnit.HOURS));

		int expired = expirationService.expireDueSettlements(memberId);

		assertThat(expired).isEqualTo(2);
		assertThat(expireTransactionCount(memberId)).isEqualTo(2);
		assertThat(settlementRow(firstTrip).get("expired_at")).isNotNull();
		assertThat(settlementRow(secondTrip).get("expired_at")).isNotNull();
	}

	@Test
	@DisplayName("만료 도래 정산이 있는 모든 활성 회원을 순회해 만료를 확정한다")
	void expireAllDueProcessesEveryActiveMemberWithDueSettlements() {
		UUID firstMemberId = insertActiveMember();
		UUID firstTrip = insertTrip(firstMemberId);
		insertCarryOverSettlement(firstTrip, 100, Instant.now().minus(241, ChronoUnit.HOURS));
		UUID secondMemberId = insertActiveMember();
		UUID secondTrip = insertTrip(secondMemberId);
		insertCarryOverSettlement(secondTrip, 50, Instant.now().minus(300, ChronoUnit.HOURS));
		UUID notYetDueMemberId = insertActiveMember();
		UUID notYetDueTrip = insertTrip(notYetDueMemberId);
		insertCarryOverSettlement(notYetDueTrip, 40, Instant.now().minus(1, ChronoUnit.HOURS));

		int expired = expirationService.expireAllDue();

		assertThat(expired).isEqualTo(2);
		assertThat(settlementRow(firstTrip).get("expired_at")).isNotNull();
		assertThat(settlementRow(secondTrip).get("expired_at")).isNotNull();
		assertThat(settlementRow(notYetDueTrip).get("expired_at")).isNull();
	}

	@Test
	@DisplayName("한 회원의 만료 확정 실패는 다른 회원 처리를 막지 않으며 실패한 회원은 다음 실행에서 재시도한다")
	void isolatesOneMemberFailureAndRetriesOnNextRun() {
		UUID failingMemberId = insertActiveMember();
		UUID failingTrip = insertTrip(failingMemberId);
		insertCarryOverSettlement(failingTrip, 100, Instant.now().minus(241, ChronoUnit.HOURS));
		UUID healthyMemberId = insertActiveMember();
		UUID healthyTrip = insertTrip(healthyMemberId);
		insertCarryOverSettlement(healthyTrip, 100, Instant.now().minus(241, ChronoUnit.HOURS));

		jdbcTemplate.execute("CREATE FUNCTION fail_point_transactions_insert() RETURNS trigger AS $$ " + "BEGIN "
				+ "    IF NEW.member_id = '" + failingMemberId + "' THEN "
				+ "        RAISE EXCEPTION 'forced expiration failure'; " + "    END IF; " + "    RETURN NEW; "
				+ "END; " + "$$ LANGUAGE plpgsql");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_point_transactions_insert
				BEFORE INSERT ON point_transactions
				FOR EACH ROW EXECUTE FUNCTION fail_point_transactions_insert()
				""");

		int expired = expirationService.expireAllDue();

		assertThat(expired).isEqualTo(1);
		assertThat(settlementRow(failingTrip).get("expired_at")).isNull();
		assertThat(settlementRow(healthyTrip).get("expired_at")).isNotNull();

		jdbcTemplate.execute("DROP TRIGGER fail_point_transactions_insert ON point_transactions");
		jdbcTemplate.execute("DROP FUNCTION fail_point_transactions_insert()");

		assertThat(expirationService.expireAllDue()).isEqualTo(1);
		assertThat(settlementRow(failingTrip).get("expired_at")).isNotNull();
	}

	@Test
	@DisplayName("같은 정산의 동시 만료 처리 요청에도 EXPIRE 내역과 잔액 차감은 한 번만 확정된다")
	void concurrentExpirationOfSameSettlementProducesExactlyOneExpireTransaction() throws Exception {
		UUID memberId = insertActiveMember();
		UUID tripId = insertTrip(memberId);
		insertCarryOverSettlement(tripId, 300, Instant.now().minus(241, ChronoUnit.HOURS));

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> task = () -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("동시 실행 시작 신호를 받지 못했습니다.");
				}
				return expirationService.expireDueSettlements(memberId);
			};
			Future<Integer> first = executor.submit(task);
			Future<Integer> second = executor.submit(task);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int totalExpired = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);
			assertThat(totalExpired).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}

		assertThat(expireTransactionCount(memberId)).isEqualTo(1);
		Map<String, Object> transaction = onlyExpireTransaction(memberId);
		assertThat(transaction.get("amount")).isEqualTo(-300L);
	}

	@Test
	@DisplayName("만료액이 직전 확정 잔액보다 크면 만료 전체를 롤백하고 음수 잔액을 만들지 않는다")
	void doesNotExpireMorePointsThanCurrentBalance() {
		UUID memberId = insertActiveMember();
		UUID tripId = insertTrip(memberId);
		insertCarryOverSettlement(tripId, 200, Instant.now().minus(241, ChronoUnit.HOURS));
		jdbcTemplate.update("UPDATE point_transactions SET amount = 100 WHERE trip_id = ? AND type = 'EARN'", tripId);

		assertThatThrownBy(() -> expirationService.expireDueSettlements(memberId))
				.isInstanceOf(IllegalStateException.class);

		assertThat(expireTransactionCount(memberId)).isZero();
		assertThat(settlementRow(tripId).get("expired_at")).isNull();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(amount), 0)::bigint FROM point_transactions WHERE member_id = ?", Long.class,
				memberId)).isEqualTo(100L);
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
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status, ended_at, settled_at) "
				+ "VALUES (?, ?, 'SETTLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", tripId, memberId);
		return tripId;
	}

	private void insertCarryOverSettlement(UUID tripId, long settledPoints, Instant settledAt) {
		UUID memberId = Objects.requireNonNull(
				jdbcTemplate.queryForObject("SELECT member_id FROM trips WHERE id = ?", UUID.class, tripId));
		jdbcTemplate.update("""
				INSERT INTO point_transactions (member_id, trip_id, type, amount, description, occurred_at)
				VALUES (?, ?, 'EARN', ?, '이월 전 적립', ?)
				""", memberId, tripId, settledPoints, Timestamp.from(settledAt));
		jdbcTemplate.update("""
				INSERT INTO point_settlements (trip_id, choice, settled_points, expires_at, settled_at)
				VALUES (?, 'CARRY_OVER', ?, ?, ?)
				""", tripId, settledPoints, Timestamp.from(settledAt.plus(240, ChronoUnit.HOURS)),
				Timestamp.from(settledAt));
	}

	private Instant currentDbTime() {
		return Objects.requireNonNull(jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class))
				.toInstant();
	}

	private Map<String, Object> settlementRow(UUID tripId) {
		return jdbcTemplate.queryForMap("SELECT * FROM point_settlements WHERE trip_id = ?", tripId);
	}

	private Map<String, Object> onlyExpireTransaction(UUID memberId) {
		List<Map<String, Object>> rows = jdbcTemplate
				.queryForList("SELECT * FROM point_transactions WHERE member_id = ? AND type = 'EXPIRE'", memberId);
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private long expireTransactionCount(UUID memberId) {
		return Objects.requireNonNull(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EXPIRE'", Long.class,
				memberId));
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
