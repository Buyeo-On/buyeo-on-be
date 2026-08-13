package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
class PushTokenIntegrationTests {

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

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_push_token_upsert ON push_tokens");
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_push_token_delete ON push_tokens");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_push_token_upsert()");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_push_token_delete()");
		jdbcTemplate.update("DELETE FROM push_tokens");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("현재 세션에 토큰을 등록한 뒤 근처 퀴즈 알림을 활성화한다")
	void registersCurrentSessionTokenAndEnablesNearbyQuizNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Map<String, Object> settingsBefore = settings(member.memberId());

		performPut(member, "{\"token\":\"fcm-current-device\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").isMap());

		assertThat(pushTokens()).singleElement().satisfies(token -> {
			assertThat(token.get("auth_session_id")).isEqualTo(member.sessionId());
			assertThat(token.get("registration_token")).isEqualTo("fcm-current-device");
		});
		assertThat(settings(member.memberId())).isEqualTo(settingsBefore);

		mockMvc.perform(patch("/members/me/settings").header("Authorization", bearer(member))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"nearbyQuizNotificationEnabled":true,"deviceNotificationPermissionGranted":true,
						 "deviceLocationPermissionGranted":true,"version":0}
						""")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nearbyQuizNotificationEnabled").value(true))
				.andExpect(jsonPath("$.data.version").value(1));
	}

	@Test
	@DisplayName("현재 세션의 동일 토큰 재등록은 DB 상태를 변경하지 않는다")
	void sameTokenRegistrationIsNoOp() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertPushToken(member.sessionId(), "same-token");
		Map<String, Object> before = pushToken(member.sessionId());

		performPut(member, "{\"token\":\"same-token\"}").andExpect(status().isOk());

		assertThat(pushToken(member.sessionId())).isEqualTo(before);
	}

	@Test
	@DisplayName("새 토큰은 현재 세션의 기존 토큰을 교체하고 다른 세션에서 소유권을 옮긴다")
	void newTokenReplacesCurrentAndMovesFromAnotherSession() throws Exception {
		AuthenticatedMember first = insertAuthenticatedMember();
		AuthenticatedMember second = insertAuthenticatedMember();
		insertPushToken(first.sessionId(), "shared-token");
		insertPushToken(second.sessionId(), "second-old-token");

		performPut(second, "{\"token\":\"shared-token\"}").andExpect(status().isOk());

		assertThat(pushTokens()).singleElement().satisfies(token -> {
			assertThat(token.get("auth_session_id")).isEqualTo(second.sessionId());
			assertThat(token.get("registration_token")).isEqualTo("shared-token");
		});
	}

	@Test
	@DisplayName("같은 토큰의 동시 등록은 확정된 세션 하나에만 연결한다")
	void concurrentRegistrationKeepsOneSessionOwner() throws Exception {
		AuthenticatedMember first = insertAuthenticatedMember();
		AuthenticatedMember second = insertAuthenticatedMember();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> firstResult = executor.submit(() -> concurrentPut(first, ready, start));
			Future<MvcResult> secondResult = executor.submit(() -> concurrentPut(second, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(firstResult.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
					secondResult.get(10, TimeUnit.SECONDS).getResponse().getStatus())).containsOnly(200);
		} finally {
			executor.shutdownNow();
		}

		assertThat(pushTokens()).singleElement().satisfies(token -> {
			assertThat(token.get("registration_token")).isEqualTo("concurrent-token");
			assertThat(token.get("auth_session_id")).isIn(first.sessionId(), second.sessionId());
		});
	}

	@Test
	@DisplayName("삭제는 현재 세션의 토큰만 제거하며 토큰이 없어도 성공한다")
	void deleteRemovesOnlyCurrentSessionAndIsIdempotent() throws Exception {
		AuthenticatedMember current = insertAuthenticatedMember();
		AuthenticatedMember other = insertAuthenticatedMember();
		insertPushToken(current.sessionId(), "current-token");
		insertPushToken(other.sessionId(), "other-token");
		Map<String, Object> settingsBefore = settings(current.memberId());

		performDelete(current).andExpect(status().isOk()).andExpect(jsonPath("$.data").isMap());
		performDelete(current).andExpect(status().isOk());

		assertThat(pushTokens()).singleElement().satisfies(token -> {
			assertThat(token.get("auth_session_id")).isEqualTo(other.sessionId());
			assertThat(token.get("registration_token")).isEqualTo("other-token");
		});
		assertThat(settings(current.memberId())).isEqualTo(settingsBefore);
	}

	@Test
	@DisplayName("엄격한 요청 계약 위반은 기존 토큰을 변경하지 않는다")
	void invalidRequestsDoNotChangeToken() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertPushToken(member.sessionId(), "existing-token");
		Map<String, Object> before = pushToken(member.sessionId());
		List<String> invalidRequests = List.of("{}", "{\"token\":null}", "{\"token\":123}", "{\"token\":\"   \"}",
				"{\"token\":\"valid\",\"unknown\":true}", "{\"token\":\"" + "a".repeat(4_097) + "\"}", "{");

		for (String request : invalidRequests) {
			performPut(member, request).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
			assertThat(pushToken(member.sessionId())).isEqualTo(before);
		}
	}

	@Test
	@DisplayName("토큰 API는 활성 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(
				put("/members/me/push-token").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"token\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
		mockMvc.perform(delete("/members/me/push-token")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));

		AuthenticatedMember member = insertAuthenticatedMember();
		String invalidSessionToken = accessTokenService.issue(member.memberId(), UUID.randomUUID());
		mockMvc.perform(put("/members/me/push-token").header("Authorization", "Bearer " + invalidSessionToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"token\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("토큰 교체 저장 실패는 기존 연결을 유지한다")
	void failedUpsertRollsBackExistingToken() {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertPushToken(member.sessionId(), "existing-token");
		Map<String, Object> before = pushToken(member.sessionId());
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_push_token_upsert() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced push token upsert failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_push_token_upsert
				BEFORE INSERT OR UPDATE ON push_tokens
				FOR EACH ROW EXECUTE FUNCTION fail_push_token_upsert()
				""");

		assertThatThrownBy(() -> performPut(member, "{\"token\":\"new-token\"}").andReturn())
				.isInstanceOf(Exception.class);
		assertThat(pushToken(member.sessionId())).isEqualTo(before);
	}

	@Test
	@DisplayName("토큰 삭제 저장 실패는 기존 연결을 유지한다")
	void failedDeleteRollsBackExistingToken() {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertPushToken(member.sessionId(), "existing-token");
		Map<String, Object> before = pushToken(member.sessionId());
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_push_token_delete() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced push token delete failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_push_token_delete
				BEFORE DELETE ON push_tokens
				FOR EACH ROW EXECUTE FUNCTION fail_push_token_delete()
				""");

		assertThatThrownBy(() -> performDelete(member).andReturn()).isInstanceOf(Exception.class);
		assertThat(pushToken(member.sessionId())).isEqualTo(before);
	}

	private MvcResult concurrentPut(AuthenticatedMember member, CountDownLatch ready, CountDownLatch start)
			throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return performPut(member, "{\"token\":\"concurrent-token\"}").andReturn();
	}

	private org.springframework.test.web.servlet.ResultActions performPut(AuthenticatedMember member, String body)
			throws Exception {
		return mockMvc.perform(put("/members/me/push-token").header("Authorization", bearer(member))
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private org.springframework.test.web.servlet.ResultActions performDelete(AuthenticatedMember member)
			throws Exception {
		return mockMvc.perform(delete("/members/me/push-token").header("Authorization", bearer(member)));
	}

	private String bearer(AuthenticatedMember member) {
		return "Bearer " + member.accessToken();
	}

	private AuthenticatedMember insertAuthenticatedMember() {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO member_settings
				    (member_id, nearby_quiz_notification_enabled, dark_mode_enabled, version)
				VALUES (?, false, false, 0)
				""", memberId);
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
		return new AuthenticatedMember(memberId, sessionId, accessTokenService.issue(memberId, sessionId));
	}

	private void insertPushToken(UUID sessionId, String registrationToken) {
		jdbcTemplate.update("""
				INSERT INTO push_tokens (auth_session_id, registration_token, created_at, updated_at)
				VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP - INTERVAL '1 hour')
				""", sessionId, registrationToken);
	}

	private List<Map<String, Object>> pushTokens() {
		return jdbcTemplate.queryForList("""
				SELECT auth_session_id, registration_token, created_at, updated_at
				FROM push_tokens
				ORDER BY registration_token
				""");
	}

	private Map<String, Object> pushToken(UUID sessionId) {
		return jdbcTemplate.queryForMap("""
				SELECT auth_session_id, registration_token, created_at, updated_at
				FROM push_tokens
				WHERE auth_session_id = ?
				""", sessionId);
	}

	private Map<String, Object> settings(UUID memberId) {
		return jdbcTemplate.queryForMap("""
				SELECT nearby_quiz_notification_enabled, dark_mode_enabled, version
				FROM member_settings
				WHERE member_id = ?
				""", memberId);
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

	private record AuthenticatedMember(UUID memberId, UUID sessionId, String accessToken) {
	}
}
