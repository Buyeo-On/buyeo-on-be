package com.buyeoon.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PointSettlementPreviewIntegrationTests {

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

	/** 종료되고 미정산인 본인 여행은 해당 여행의 EARN 합계와 전체 잔액, 240시간 이월 기간을 반환한다. */
	@Test
	@DisplayName("종료되고 미정산인 본인 여행은 settleablePoints, currentBalance, carryOverDurationHours를 반환한다")
	void returnsPreviewForOwnedEndedTrip() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(member.memberId(), tripId, "EARN", 200, "미션 보상");
		insertTransaction(member.memberId(), tripId, "EARN", 100, "미션 보상");

		mockMvc.perform(previewRequest(tripId)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.tripId").value(tripId.toString()))
				.andExpect(jsonPath("$.data.settleablePoints").value(300))
				.andExpect(jsonPath("$.data.currentBalance").value(300))
				.andExpect(jsonPath("$.data.carryOverDurationHours").value(240));
	}

	/** settleablePoints는 해당 여행의 EARN 합계만 포함하고, 다른 여행 이월분과 ADJUST는 제외한다. */
	@Test
	@DisplayName("settleablePoints는 다른 여행 이월분과 ADJUST를 제외한 해당 여행 EARN 합계다")
	void settleablePointsExcludesOtherTripsAndAdjust() throws Exception {
		UUID otherTrip = insertSettledTrip(member.memberId());
		insertTransaction(member.memberId(), otherTrip, "EARN", 1000, "다른 여행 적립");
		insertCarryOverSettlement(otherTrip, 1000, Instant.now().plus(5, ChronoUnit.DAYS));

		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(member.memberId(), tripId, "EARN", 150, "미션 보상");
		insertTransaction(member.memberId(), tripId, "ADJUST", 50, "운영 조정");

		mockMvc.perform(previewRequest(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.settleablePoints").value(150))
				.andExpect(jsonPath("$.data.currentBalance").value(1200));
	}

	/** currentBalance는 만료 도래 이월분을 먼저 EXPIRE로 반영한 전체 잔액이다. */
	@Test
	@DisplayName("만료 도래 이월분을 먼저 반영한 currentBalance를 반환한다")
	void currentBalanceReflectsDueExpirationFirst() throws Exception {
		Instant expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES);
		insertTransaction(member.memberId(), null, "EARN", 400, "미션 보상", expiresAt.minus(241, ChronoUnit.HOURS));
		UUID expiredTrip = insertSettledTrip(member.memberId());
		insertCarryOverSettlement(expiredTrip, 300, expiresAt);

		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(member.memberId(), tripId, "EARN", 50, "미션 보상");

		mockMvc.perform(previewRequest(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.settleablePoints").value(50))
				.andExpect(jsonPath("$.data.currentBalance").value(150));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'EXPIRE'", Integer.class,
				member.memberId())).isEqualTo(1);
	}

	/** 정산 대상 포인트가 없어도 0으로 정상 조회된다. */
	@Test
	@DisplayName("정산 대상 포인트가 없으면 settleablePoints 0을 반환한다")
	void returnsZeroWhenNoSettleablePoints() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());

		mockMvc.perform(previewRequest(tripId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.settleablePoints").value(0))
				.andExpect(jsonPath("$.data.currentBalance").value(0));
	}

	/** 존재하지 않는 여행은 404를 반환한다. */
	@Test
	@DisplayName("존재하지 않는 여행은 404를 반환한다")
	void returns404ForNonexistentTrip() throws Exception {
		mockMvc.perform(previewRequest(UUID.randomUUID())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 다른 회원이 소유한 여행은 존재하지 않는 여행과 구분하지 않고 404를 반환한다. */
	@Test
	@DisplayName("다른 회원 소유 여행은 404를 반환한다")
	void returns404ForTripOwnedByOtherMember() throws Exception {
		AuthenticatedMember other = insertAuthenticatedMember();
		UUID otherTripId = insertEndedTrip(other.memberId());

		mockMvc.perform(previewRequest(otherTripId)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 진행 중인 여행은 409를 반환한다. */
	@Test
	@DisplayName("진행 중인 여행은 409를 반환한다")
	void returns409ForInProgressTrip() throws Exception {
		UUID tripId = insertInProgressTrip(member.memberId());

		mockMvc.perform(previewRequest(tripId)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** 이미 정산된 여행은 409를 반환한다. */
	@Test
	@DisplayName("이미 정산된 여행은 409를 반환한다")
	void returns409ForSettledTrip() throws Exception {
		UUID tripId = insertSettledTrip(member.memberId());

		mockMvc.perform(previewRequest(tripId)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** 인증하지 않으면 401을 받는다. */
	@Test
	@DisplayName("인증하지 않으면 401을 받는다")
	void returns401WhenUnauthenticated() throws Exception {
		mockMvc.perform(get("/trips/{tripId}/settlement-preview", UUID.randomUUID()))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 탈퇴한 회원은 본인 여행이라도 미리보기를 조회할 수 없다. */
	@Test
	@DisplayName("탈퇴한 회원은 401을 받는다")
	void returns401ForWithdrawnMember() throws Exception {
		UUID tripId = insertEndedTrip(member.memberId());
		jdbcTemplate.update("""
				UPDATE members
				SET status = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP,
				    purge_after = CURRENT_TIMESTAMP + INTERVAL '30 days'
				WHERE id = ?
				""", member.memberId());

		mockMvc.perform(previewRequest(tripId)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private MockHttpServletRequestBuilder previewRequest(UUID tripId) {
		return get("/trips/{tripId}/settlement-preview", tripId).header("Authorization",
				"Bearer " + member.accessToken());
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

	private void insertTransaction(UUID memberId, UUID tripId, String type, long amount, String description) {
		insertTransaction(memberId, tripId, type, amount, description, Instant.now());
	}

	private void insertTransaction(UUID memberId, UUID tripId, String type, long amount, String description,
			Instant occurredAt) {
		jdbcTemplate.update(
				"INSERT INTO point_transactions (id, member_id, trip_id, type, amount, description, occurred_at) "
						+ "VALUES (?, ?, ?, ?::point_transaction_type, ?, ?, ?)",
				UUID.randomUUID(), memberId, tripId, type, amount, description, Timestamp.from(occurredAt));
	}

	private UUID insertInProgressTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status) VALUES (?, ?, 'IN_PROGRESS')", tripId, memberId);
		return tripId;
	}

	private UUID insertEndedTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status, ended_at) VALUES (?, ?, 'ENDED', now())", tripId,
				memberId);
		return tripId;
	}

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
