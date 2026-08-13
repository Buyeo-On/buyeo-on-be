package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.member.auth.RefreshTokenService;
import com.buyeoon.member.auth.RefreshTokenService.IssuedRefreshToken;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MemberWithdrawalIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

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
	private RefreshTokenService refreshTokenService;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_withdraw_session_update ON auth_sessions");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_withdraw_session_update()");
		jdbcTemplate.update("DELETE FROM push_tokens");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("탈퇴는 회원을 즉시 차단하고 모든 세션과 푸시 토큰을 폐기한다")
	void withdrawalBlocksMemberAndRevokesEverySession() throws Exception {
		UUID memberId = insertMember();
		AuthenticatedSession current = insertSession(memberId);
		AuthenticatedSession other = insertSession(memberId);
		insertPushToken(current.sessionId(), "current-device-token");
		insertPushToken(other.sessionId(), "other-device-token");

		performWithdrawal(current.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").isMap());

		Map<String, Object> member = member(memberId);
		Instant withdrawnAt = ((Timestamp) member.get("withdrawn_at")).toInstant();
		Instant purgeAfter = ((Timestamp) member.get("purge_after")).toInstant();
		assertThat(member.get("status").toString()).isEqualTo("WITHDRAWN");
		assertThat(Duration.between(withdrawnAt, purgeAfter)).isEqualTo(Duration.ofDays(30));
		assertThat(activeSessionCount(memberId)).isZero();
		assertThat(pushTokenCount(memberId)).isZero();
		assertThat(settingsCount(memberId)).isEqualTo(1);

		for (AuthenticatedSession session : List.of(current, other)) {
			mockMvc.perform(get("/members/me").header("Authorization", bearer(session.accessToken())))
					.andExpect(status().isUnauthorized());
			performRefresh(session.refreshToken()).andExpect(status().isUnauthorized());
		}
	}

	@Test
	@DisplayName("탈퇴에는 활성 인증이 필요하다")
	void activeAuthenticationIsRequired() throws Exception {
		mockMvc.perform(delete("/members/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));

		UUID memberId = insertMember();
		AuthenticatedSession session = insertSession(memberId);
		jdbcTemplate.update("UPDATE auth_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?",
				session.sessionId());

		performWithdrawal(session.accessToken()).andExpect(status().isUnauthorized());
		assertThat(member(memberId).get("status").toString()).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("동시 탈퇴 요청은 하나의 탈퇴 상태로 끝난다")
	void concurrentWithdrawalsEndInOneWithdrawnState() throws Exception {
		UUID memberId = insertMember();
		AuthenticatedSession first = insertSession(memberId);
		AuthenticatedSession second = insertSession(memberId);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<Integer> statuses;

		try {
			Future<MvcResult> firstRequest = executor.submit(
					() -> concurrentRequest(ready, start, () -> performWithdrawal(first.accessToken()).andReturn()));
			Future<MvcResult> secondRequest = executor.submit(
					() -> concurrentRequest(ready, start, () -> performWithdrawal(second.accessToken()).andReturn()));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			statuses = List.of(firstRequest.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
					secondRequest.get(10, TimeUnit.SECONDS).getResponse().getStatus());
		} finally {
			executor.shutdownNow();
		}

		assertThat(statuses).allMatch(status -> status == 200 || status == 401).contains(200);
		assertThat(member(memberId).get("status").toString()).isEqualTo("WITHDRAWN");
		assertThat(activeSessionCount(memberId)).isZero();
	}

	@Test
	@DisplayName("설정 변경과 탈퇴가 경합해도 최종 상태는 탈퇴다")
	void concurrentSettingsUpdateAndWithdrawalEndWithdrawn() throws Exception {
		UUID memberId = insertMember();
		AuthenticatedSession session = insertSession(memberId);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> settings = executor.submit(() -> concurrentRequest(ready, start,
					() -> performSettingsUpdate(session.accessToken()).andReturn()));
			Future<MvcResult> withdrawal = executor.submit(
					() -> concurrentRequest(ready, start, () -> performWithdrawal(session.accessToken()).andReturn()));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(withdrawal.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
			assertThat(settings.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isIn(200, 401);
		} finally {
			executor.shutdownNow();
		}

		assertThat(member(memberId).get("status").toString()).isEqualTo("WITHDRAWN");
		assertThat(activeSessionCount(memberId)).isZero();
	}

	@Test
	@DisplayName("세션 폐기 실패는 회원 상태와 푸시 토큰 삭제를 모두 롤백한다")
	void sessionRevocationFailureRollsBackWithdrawal() {
		UUID memberId = insertMember();
		AuthenticatedSession session = insertSession(memberId);
		insertPushToken(session.sessionId(), "rollback-device-token");
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_withdraw_session_update() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced withdrawal session failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_withdraw_session_update
				BEFORE UPDATE ON auth_sessions
				FOR EACH ROW EXECUTE FUNCTION fail_withdraw_session_update()
				""");

		assertThatThrownBy(() -> performWithdrawal(session.accessToken()).andReturn()).isInstanceOf(Exception.class);

		Map<String, Object> member = member(memberId);
		assertThat(member.get("status").toString()).isEqualTo("ACTIVE");
		assertThat(member.get("withdrawn_at")).isNull();
		assertThat(member.get("purge_after")).isNull();
		assertThat(activeSessionCount(memberId)).isEqualTo(1);
		assertThat(pushTokenCount(memberId)).isEqualTo(1);
	}

	private MvcResult concurrentRequest(CountDownLatch ready, CountDownLatch start, Request request) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return request.perform();
	}

	private org.springframework.test.web.servlet.ResultActions performWithdrawal(String accessToken) throws Exception {
		return mockMvc.perform(delete("/members/me").header("Authorization", bearer(accessToken)));
	}

	private org.springframework.test.web.servlet.ResultActions performSettingsUpdate(String accessToken)
			throws Exception {
		return mockMvc.perform(patch("/members/me/settings").header("Authorization", bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content("{\"darkModeEnabled\":true,\"version\":0}"));
	}

	private org.springframework.test.web.servlet.ResultActions performRefresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
	}

	private String bearer(String accessToken) {
		return "Bearer " + accessToken;
	}

	private UUID insertMember() {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO member_settings
				    (member_id, nearby_quiz_notification_enabled, dark_mode_enabled, version)
				VALUES (?, false, false, 0)
				""", memberId);
		return memberId;
	}

	private AuthenticatedSession insertSession(UUID memberId) {
		UUID sessionId = UUID.randomUUID();
		IssuedRefreshToken refreshToken = refreshTokenService.issue(sessionId);
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, memberId, refreshToken.hash(), Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
		return new AuthenticatedSession(sessionId, accessTokenService.issue(memberId, sessionId), refreshToken.token());
	}

	private void insertPushToken(UUID sessionId, String token) {
		jdbcTemplate.update("INSERT INTO push_tokens (auth_session_id, registration_token) VALUES (?, ?)", sessionId,
				token);
	}

	private Map<String, Object> member(UUID memberId) {
		return jdbcTemplate.queryForMap("SELECT * FROM members WHERE id = ?", memberId);
	}

	private long activeSessionCount(UUID memberId) {
		return Objects.requireNonNull(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM auth_sessions WHERE member_id = ? AND revoked_at IS NULL", Long.class, memberId));
	}

	private long pushTokenCount(UUID memberId) {
		return Objects.requireNonNull(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM push_tokens token
				JOIN auth_sessions session ON session.id = token.auth_session_id
				WHERE session.member_id = ?
				""", Long.class, memberId));
	}

	private long settingsCount(UUID memberId) {
		return Objects.requireNonNull(jdbcTemplate
				.queryForObject("SELECT count(*) FROM member_settings WHERE member_id = ?", Long.class, memberId));
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> APPLICATION_USERNAME);
		registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	@FunctionalInterface
	private interface Request {

		MvcResult perform() throws Exception;
	}

	private record AuthenticatedSession(UUID sessionId, String accessToken, String refreshToken) {
	}
}
