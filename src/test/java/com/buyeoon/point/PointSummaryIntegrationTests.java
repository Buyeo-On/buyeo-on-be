package com.buyeoon.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.point.application.PointExpirationService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PointSummaryIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private org.springframework.test.web.servlet.MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private PointExpirationService pointExpirationService;

	private AuthenticatedMember member;

	@BeforeEach
	void setUpMember() {
		member = insertAuthenticatedMember();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM point_settlements");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 잔액은 전체 내역 amount 합계이고, 누적 적립은 EARN 내역 amount 합계다. */
	@Test
	@DisplayName("잔액은 전체 내역 합계, 누적 적립은 EARN 내역 합계로 반환된다")
	void returnsBalanceAndCumulativeEarnedFromTransactions() throws Exception {
		insertTransaction(member.memberId(), "EARN", 100, "미션 보상");
		insertTransaction(member.memberId(), "EARN", 50, "미션 보상");
		insertTransaction(member.memberId(), "LEAVE_TO_BUYEO", -30, "정산");

		mockMvc.perform(pointsRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.balance").value(120))
				.andExpect(jsonPath("$.data.cumulativeEarned").value(150));
	}

	/** 포인트 내역이 없으면 잔액과 누적 적립은 0, 만료 예정은 빈 목록이다. */
	@Test
	@DisplayName("내역이 없으면 balance 0, cumulativeEarned 0, expirations 빈 배열을 받는다")
	void returnsZeroAndEmptyExpirationsWhenNoTransactions() throws Exception {
		mockMvc.perform(pointsRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value(0))
				.andExpect(jsonPath("$.data.cumulativeEarned").value(0))
				.andExpect(jsonPath("$.data.expirations").isArray())
				.andExpect(jsonPath("$.data.expirations.length()").value(0));
	}

	/** 만료 예정은 CARRY_OVER 정산 중 만료 시각이 미래인 항목만 만료 시각 오름차순으로 반환한다. */
	@Test
	@DisplayName("만료 예정은 미래 CARRY_OVER 정산만 만료 시각 오름차순으로 반환한다")
	void returnsFutureCarryOverExpirationsInAscendingOrder() throws Exception {
		Instant soon = Instant.now().plus(10, ChronoUnit.DAYS);
		Instant later = Instant.now().plus(20, ChronoUnit.DAYS);
		Instant past = Instant.now().minus(1, ChronoUnit.DAYS);

		UUID laterTrip = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(laterTrip, 200, later);
		UUID soonTrip = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(soonTrip, 100, soon);
		UUID pastTrip = insertSettledTrip(member.memberId());
		insertTransaction(member.memberId(), "EARN", 999, "과거 이월 전 적립", past.minus(241, ChronoUnit.HOURS));
		insertCarryOverSettlement(pastTrip, 999, past);
		UUID leaveTrip = insertSettledTrip(member.memberId());
		insertLeaveToBuyeoSettlement(leaveTrip, 300);

		mockMvc.perform(pointsRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.expirations.length()").value(2))
				.andExpect(jsonPath("$.data.expirations[0].points").value(100))
				.andExpect(jsonPath("$.data.expirations[1].points").value(200));
	}

	/** 만료 도래 이월분은 요약을 계산하기 전에 EXPIRE로 확정하고 감소한 잔액과 유효한 만료 예정만 반환한다. */
	@Test
	@DisplayName("만료 도래 이월분을 먼저 확정하고 감소한 잔액과 유효한 만료 예정만 반환한다")
	void expiresDueCarryOverBeforeReturningSummary() throws Exception {
		Instant expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES);
		insertTransaction(member.memberId(), "EARN", 400, "미션 보상", expiresAt.minus(241, ChronoUnit.HOURS));
		UUID expiredTrip = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(expiredTrip, 300, expiresAt);
		UUID activeTrip = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(activeTrip, 100, Instant.now().plus(1, ChronoUnit.DAYS));

		mockMvc.perform(pointsRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value(100))
				.andExpect(jsonPath("$.data.cumulativeEarned").value(400))
				.andExpect(jsonPath("$.data.expirations.length()").value(1))
				.andExpect(jsonPath("$.data.expirations[0].points").value(100));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EXPIRE'", Integer.class,
				member.memberId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT expired_at IS NOT NULL FROM point_settlements WHERE trip_id = ?",
				Boolean.class, expiredTrip)).isTrue();
	}

	/** 요약 조회와 scheduler가 경합해도 같은 만료분은 한 번만 차감하고 확정 잔액을 반환한다. */
	@Test
	@DisplayName("요약 조회와 scheduler 경합에도 EXPIRE를 한 번만 확정한다")
	void summaryAndSchedulerExpireSameCarryOverExactlyOnce() throws Exception {
		Instant expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES);
		insertTransaction(member.memberId(), "EARN", 300, "미션 보상", expiresAt.minus(241, ChronoUnit.HOURS));
		UUID expiredTrip = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(expiredTrip, 200, expiresAt);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> summary = executor.submit(() -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("동시 실행 시작 신호를 받지 못했습니다.");
				}
				return mockMvc.perform(pointsRequest()).andReturn();
			});
			Future<Integer> scheduler = executor.submit(() -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("동시 실행 시작 신호를 받지 못했습니다.");
				}
				return pointExpirationService.expireAllDue();
			});
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult result = summary.get(10, TimeUnit.SECONDS);
			scheduler.get(10, TimeUnit.SECONDS);
			assertThat(result.getResponse().getStatus()).isEqualTo(200);
			assertThat(JsonPath.<Integer>read(result.getResponse().getContentAsString(), "$.data.balance"))
					.isEqualTo(100);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EXPIRE'", Integer.class,
				member.memberId())).isEqualTo(1);
	}

	/** 탈퇴가 먼저 확정된 회원은 기존 토큰으로 포인트를 조회하거나 만료를 확정하지 못한다. */
	@Test
	@DisplayName("탈퇴가 먼저 확정되면 포인트 조회와 만료 확정을 거부한다")
	void rejectsPointSummaryAfterWithdrawalCompleted() throws Exception {
		Instant expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES);
		insertTransaction(member.memberId(), "EARN", 100, "미션 보상", expiresAt.minus(241, ChronoUnit.HOURS));
		UUID expiredTrip = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(expiredTrip, 100, expiresAt);
		jdbcTemplate.update("""
				UPDATE members
				SET status = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP,
				    purge_after = CURRENT_TIMESTAMP + INTERVAL '30 days'
				WHERE id = ?
				""", member.memberId());

		mockMvc.perform(pointsRequest()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));

		assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM members WHERE id = ?", String.class,
				member.memberId())).isEqualTo("WITHDRAWN");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EXPIRE'", Integer.class,
				member.memberId())).isZero();
	}

	/** 다른 회원의 내역과 정산은 조회 결과에 포함되지 않는다. */
	@Test
	@DisplayName("다른 회원의 포인트 내역과 정산은 포함되지 않는다")
	void excludesOtherMembersData() throws Exception {
		AuthenticatedMember other = insertAuthenticatedMember();
		insertTransaction(other.memberId(), "EARN", 1000, "다른 회원 적립");
		UUID otherTrip = insertSettledTrip(other.memberId());
		insertCarryOverSettlement(otherTrip, 500, Instant.now().plus(5, ChronoUnit.DAYS));

		mockMvc.perform(pointsRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value(0))
				.andExpect(jsonPath("$.data.cumulativeEarned").value(0))
				.andExpect(jsonPath("$.data.expirations.length()").value(0));
	}

	/** 인증하지 않으면 401을 받는다. */
	@Test
	@DisplayName("인증하지 않으면 401을 받는다")
	void returns401WhenUnauthenticated() throws Exception {
		mockMvc.perform(get("/members/me/points")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private MockHttpServletRequestBuilder pointsRequest() {
		return get("/members/me/points").header("Authorization", "Bearer " + member.accessToken());
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

	private void insertTransaction(UUID memberId, String type, long amount, String description) {
		insertTransaction(memberId, type, amount, description, Instant.now());
	}

	/** 지정한 발생 시각으로 포인트 내역 fixture를 저장한다. */
	private void insertTransaction(UUID memberId, String type, long amount, String description, Instant occurredAt) {
		jdbcTemplate.update(
				"INSERT INTO point_transactions (id, member_id, type, amount, description, occurred_at) "
						+ "VALUES (?, ?, ?::point_transaction_type, ?, ?, ?)",
				UUID.randomUUID(), memberId, type, amount, description, Timestamp.from(occurredAt));
	}

	/** 정산 대상 여행. 여러 여행을 같은 회원에 만들기 위해 진행 중 상태 대신 정산 완료 상태로 만든다. */
	private UUID insertSettledTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO trips (id, member_id, status, ended_at, settled_at) VALUES (?, ?, 'SETTLED', now(), now())",
				tripId, memberId);
		return tripId;
	}

	private void insertCarryOverSettlement(UUID tripId, long settledPoints, Instant expiresAt) {
		Instant settledAt = expiresAt.minus(240, ChronoUnit.HOURS);
		jdbcTemplate.update(
				"INSERT INTO point_settlements (id, trip_id, choice, settled_points, expires_at, settled_at) "
						+ "VALUES (?, ?, 'CARRY_OVER', ?, ?, ?)",
				UUID.randomUUID(), tripId, settledPoints, Timestamp.from(expiresAt), Timestamp.from(settledAt));
	}

	private void insertLeaveToBuyeoSettlement(UUID tripId, long settledPoints) {
		jdbcTemplate.update("INSERT INTO point_settlements (id, trip_id, choice, settled_points, expires_at) "
				+ "VALUES (?, ?, 'LEAVE_TO_BUYEO', ?, NULL)", UUID.randomUUID(), tripId, settledPoints);
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
