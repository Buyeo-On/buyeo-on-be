package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.member.auth.RefreshTokenService;
import com.buyeoon.member.auth.RefreshTokenService.IssuedRefreshToken;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LogoutIntegrationTests {

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

	@Autowired
	private ObjectMapper objectMapper;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_logout_session_update ON auth_sessions");
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_logout_push_delete ON push_tokens");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_logout_session_update()");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_logout_push_delete()");
		jdbcTemplate.update("DELETE FROM push_tokens");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("로그아웃은 현재 세션과 푸시 토큰만 종료한다")
	void logoutEndsOnlyCurrentSessionAndPushToken() throws Exception {
		UUID memberId = insertMember();
		AuthenticatedSession current = insertSession(memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);
		AuthenticatedSession other = insertSession(memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);
		insertPushToken(current.sessionId(), "current-token");
		insertPushToken(other.sessionId(), "other-token");
		Map<String, Object> settingsBefore = settings(memberId);

		performLogout(current.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").isMap());

		assertThat(session(current.sessionId()).get("revoked_at")).isNotNull();
		assertThat(session(other.sessionId()).get("revoked_at")).isNull();
		assertThat(pushTokenCount(current.sessionId())).isZero();
		assertThat(pushTokenCount(other.sessionId())).isEqualTo(1);
		assertThat(settings(memberId)).isEqualTo(settingsBefore);
		mockMvc.perform(get("/members/me").header("Authorization", bearer(current.accessToken())))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/members/me").header("Authorization", bearer(other.accessToken())))
				.andExpect(status().isOk());
		performRefresh(current.refreshToken()).andExpect(status().isUnauthorized());
		performLogout(current.accessToken()).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("인증하지 않았거나 만료·폐기된 세션은 로그아웃할 수 없다")
	void authenticationAndActiveSessionAreRequired() throws Exception {
		mockMvc.perform(post("/auth/logout")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
		UUID memberId = insertMember();
		AuthenticatedSession expired = insertSession(memberId, Instant.now().minusSeconds(1), null);
		AuthenticatedSession revoked = insertSession(memberId, Instant.now().plus(30, ChronoUnit.DAYS), Instant.now());

		performLogout(expired.accessToken()).andExpect(status().isUnauthorized());
		performLogout(revoked.accessToken()).andExpect(status().isUnauthorized());
		assertThat(session(expired.sessionId()).get("revoked_at")).isNull();
		assertThat(session(revoked.sessionId()).get("revoked_at")).isNotNull();
	}

	@Test
	@DisplayName("로그아웃과 토큰 갱신이 동시에 실행돼도 최종 세션은 폐기된다")
	void concurrentLogoutAndRefreshEndWithRevokedSession() throws Exception {
		UUID memberId = insertMember();
		AuthenticatedSession current = insertSession(memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		MvcResult logoutResult;
		MvcResult refreshResult;

		try {
			Future<MvcResult> logout = executor.submit(
					() -> concurrentRequest(ready, start, () -> performLogout(current.accessToken()).andReturn()));
			Future<MvcResult> refresh = executor.submit(
					() -> concurrentRequest(ready, start, () -> performRefresh(current.refreshToken()).andReturn()));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			logoutResult = logout.get(10, TimeUnit.SECONDS);
			refreshResult = refresh.get(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		assertThat(logoutResult.getResponse().getStatus()).isEqualTo(200);
		assertThat(refreshResult.getResponse().getStatus()).isIn(200, 401);
		assertThat(session(current.sessionId()).get("revoked_at")).isNotNull();
		if (refreshResult.getResponse().getStatus() == 200) {
			JsonNode data = objectMapper.readTree(refreshResult.getResponse().getContentAsString()).get("data");
			mockMvc.perform(get("/members/me").header("Authorization", bearer(data.get("accessToken").stringValue())))
					.andExpect(status().isUnauthorized());
			performRefresh(data.get("refreshToken").stringValue()).andExpect(status().isUnauthorized());
		}
	}

	@Test
	@DisplayName("세션 폐기 저장 실패는 푸시 토큰 삭제도 롤백한다")
	void failedSessionUpdateRollsBackPushTokenDelete() {
		UUID memberId = insertMember();
		AuthenticatedSession current = insertSession(memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);
		insertPushToken(current.sessionId(), "current-token");
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_logout_session_update() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced logout session failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_logout_session_update
				BEFORE UPDATE ON auth_sessions
				FOR EACH ROW EXECUTE FUNCTION fail_logout_session_update()
				""");

		assertThatThrownBy(() -> performLogout(current.accessToken()).andReturn()).isInstanceOf(Exception.class);
		assertThat(session(current.sessionId()).get("revoked_at")).isNull();
		assertThat(pushTokenCount(current.sessionId())).isEqualTo(1);
	}

	@Test
	@DisplayName("푸시 토큰 삭제 실패는 세션을 폐기하지 않는다")
	void failedPushTokenDeleteKeepsSessionActive() {
		UUID memberId = insertMember();
		AuthenticatedSession current = insertSession(memberId, Instant.now().plus(30, ChronoUnit.DAYS), null);
		insertPushToken(current.sessionId(), "current-token");
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_logout_push_delete() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced logout push token failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_logout_push_delete
				BEFORE DELETE ON push_tokens
				FOR EACH ROW EXECUTE FUNCTION fail_logout_push_delete()
				""");

		assertThatThrownBy(() -> performLogout(current.accessToken()).andReturn()).isInstanceOf(Exception.class);
		assertThat(session(current.sessionId()).get("revoked_at")).isNull();
		assertThat(pushTokenCount(current.sessionId())).isEqualTo(1);
	}

	private MvcResult concurrentRequest(CountDownLatch ready, CountDownLatch start, Request request) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return request.perform();
	}

	private org.springframework.test.web.servlet.ResultActions performLogout(String accessToken) throws Exception {
		return mockMvc.perform(post("/auth/logout").header("Authorization", bearer(accessToken)));
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

	private AuthenticatedSession insertSession(UUID memberId, Instant expiresAt, Instant revokedAt) {
		UUID sessionId = UUID.randomUUID();
		IssuedRefreshToken refreshToken = refreshTokenService.issue(sessionId);
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at, revoked_at)
				VALUES (?, ?, ?, ?, ?)
				""", sessionId, memberId, refreshToken.hash(), Timestamp.from(expiresAt),
				revokedAt == null ? null : Timestamp.from(revokedAt));
		return new AuthenticatedSession(sessionId, accessTokenService.issue(memberId, sessionId), refreshToken.token());
	}

	private void insertPushToken(UUID sessionId, String token) {
		jdbcTemplate.update("INSERT INTO push_tokens (auth_session_id, registration_token) VALUES (?, ?)", sessionId,
				token);
	}

	private Map<String, Object> session(UUID sessionId) {
		return jdbcTemplate.queryForMap("""
				SELECT id, member_id, refresh_token_hash, expires_at, revoked_at
				FROM auth_sessions
				WHERE id = ?
				""", sessionId);
	}

	private long pushTokenCount(UUID sessionId) {
		return Objects.requireNonNull(jdbcTemplate
				.queryForObject("SELECT count(*) FROM push_tokens WHERE auth_session_id = ?", Long.class, sessionId));
	}

	private Map<String, Object> settings(UUID memberId) {
		return jdbcTemplate.queryForMap("SELECT * FROM member_settings WHERE member_id = ?", memberId);
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
