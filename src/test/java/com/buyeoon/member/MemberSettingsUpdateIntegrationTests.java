package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class MemberSettingsUpdateIntegrationTests {

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
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_member_settings_update ON member_settings");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_member_settings_update()");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("전달한 설정만 변경하고 실제 변경마다 버전을 증가시킨다")
	void partialAndCombinedUpdatesPreserveOmittedSettings() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(false, false, 0);

		performPatch(member, "{\"darkModeEnabled\":true,\"version\":0}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nearbyQuizNotificationEnabled").value(false))
				.andExpect(jsonPath("$.data.darkModeEnabled").value(true))
				.andExpect(jsonPath("$.data.version").value(1));
		performPatch(member, """
				{"nearbyQuizNotificationEnabled":true,"darkModeEnabled":false,
				 "deviceNotificationPermissionGranted":true,"deviceLocationPermissionGranted":true,"version":1}
				""").andExpect(status().isOk()).andExpect(jsonPath("$.data.nearbyQuizNotificationEnabled").value(true))
				.andExpect(jsonPath("$.data.darkModeEnabled").value(false))
				.andExpect(jsonPath("$.data.version").value(2));

		assertThat(settings(member.memberId())).containsEntry("nearby_quiz_notification_enabled", true)
				.containsEntry("dark_mode_enabled", false).containsEntry("version", 2L);
	}

	@Test
	@DisplayName("동일 값 요청은 설정과 버전을 변경하지 않는다")
	void sameValueIsNoOp() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(false, false, 3);
		Map<String, Object> before = settings(member.memberId());

		performPatch(member, "{\"darkModeEnabled\":false,\"version\":3}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nearbyQuizNotificationEnabled").value(false))
				.andExpect(jsonPath("$.data.darkModeEnabled").value(false))
				.andExpect(jsonPath("$.data.version").value(3));

		assertThat(settings(member.memberId())).isEqualTo(before);
	}

	@Test
	@DisplayName("알림 활성화에는 두 기기 권한이 필요하지만 다른 변경에는 필요하지 않다")
	void notificationEnablementRequiresBothPermissions() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(false, false, 0);
		Map<String, Object> before = settings(member.memberId());
		List<String> deniedRequests = List.of("{\"nearbyQuizNotificationEnabled\":true,\"version\":0}", """
				{"nearbyQuizNotificationEnabled":true,"deviceNotificationPermissionGranted":true,
				 "deviceLocationPermissionGranted":false,"version":0}
				""", """
				{"nearbyQuizNotificationEnabled":true,"deviceNotificationPermissionGranted":false,
				 "deviceLocationPermissionGranted":true,"version":0}
				""");

		for (String request : deniedRequests) {
			performPatch(member, request).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
			assertThat(settings(member.memberId())).isEqualTo(before);
		}
		performPatch(member, "{\"darkModeEnabled\":true,\"version\":0}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.version").value(1));

		AuthenticatedMember enabledMember = insertAuthenticatedMember(true, false, 0);
		performPatch(enabledMember, "{\"nearbyQuizNotificationEnabled\":false,\"version\":0}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.nearbyQuizNotificationEnabled").value(false));
	}

	@Test
	@DisplayName("엄격한 요청 계약 위반은 설정을 변경하지 않는다")
	void invalidRequestsDoNotChangeSettings() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(false, false, 0);
		Map<String, Object> before = settings(member.memberId());
		List<String> invalidRequests = List.of("{\"version\":0}",
				"{\"darkModeEnabled\":true,\"unknown\":true,\"version\":0}", "{\"darkModeEnabled\":null,\"version\":0}",
				"{\"darkModeEnabled\":\"true\",\"version\":0}", "{\"darkModeEnabled\":true,\"version\":-1}", "{");

		for (String request : invalidRequests) {
			performPatch(member, request).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
			assertThat(settings(member.memberId())).isEqualTo(before);
		}
	}

	@Test
	@DisplayName("오래된 버전은 어떤 설정도 변경하지 않는다")
	void staleVersionConflictsWithoutMutation() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(false, true, 4);
		Map<String, Object> before = settings(member.memberId());

		performPatch(member, "{\"darkModeEnabled\":false,\"version\":3}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));

		assertThat(settings(member.memberId())).isEqualTo(before);
	}

	@Test
	@DisplayName("같은 버전의 동시 변경은 하나만 성공한다")
	void concurrentUpdatesWithSameVersionCommitOnce() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(false, false, 0);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> darkMode = executor
					.submit(() -> concurrentPatch(member, "{\"darkModeEnabled\":true,\"version\":0}", ready, start));
			Future<MvcResult> nearbyQuiz = executor.submit(() -> concurrentPatch(member, """
					{"nearbyQuizNotificationEnabled":true,"deviceNotificationPermissionGranted":true,
					 "deviceLocationPermissionGranted":true,"version":0}
					""", ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(darkMode.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
					nearbyQuiz.get(10, TimeUnit.SECONDS).getResponse().getStatus()))
					.containsExactlyInAnyOrder(200, 409);
		} finally {
			executor.shutdownNow();
		}

		Map<String, Object> result = settings(member.memberId());
		assertThat(result).containsEntry("version", 1L);
		assertThat((Boolean) result.get("nearby_quiz_notification_enabled") ^ (Boolean) result.get("dark_mode_enabled"))
				.isTrue();
	}

	@Test
	@DisplayName("설정 저장 실패는 기존 설정과 버전을 유지한다")
	void storageFailureRollsBackSettingsAndVersion() {
		AuthenticatedMember member = insertAuthenticatedMember(false, false, 0);
		Map<String, Object> before = settings(member.memberId());
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_member_settings_update() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced member settings failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_member_settings_update
				BEFORE UPDATE ON member_settings
				FOR EACH ROW EXECUTE FUNCTION fail_member_settings_update()
				""");

		assertThatThrownBy(() -> performPatch(member, "{\"darkModeEnabled\":true,\"version\":0}"))
				.isInstanceOf(Exception.class);
		assertThat(settings(member.memberId())).isEqualTo(before);
	}

	@Test
	@DisplayName("서비스 설정 변경에는 활성 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(patch("/members/me/settings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"darkModeEnabled\":true,\"version\":0}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));

		AuthenticatedMember member = insertAuthenticatedMember(false, false, 0);
		mockMvc.perform(patch("/members/me/settings")
				.header("Authorization", "Bearer " + accessTokenService.issue(member.memberId(), UUID.randomUUID()))
				.contentType(MediaType.APPLICATION_JSON).content("{\"darkModeEnabled\":true,\"version\":0}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private MvcResult concurrentPatch(AuthenticatedMember member, String body, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return performPatch(member, body).andReturn();
	}

	private org.springframework.test.web.servlet.ResultActions performPatch(AuthenticatedMember member, String body)
			throws Exception {
		return mockMvc.perform(patch("/members/me/settings").header("Authorization", "Bearer " + member.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private AuthenticatedMember insertAuthenticatedMember(boolean nearbyQuizNotificationEnabled,
			boolean darkModeEnabled, long version) {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
		jdbcTemplate.update("""
				INSERT INTO member_settings
				    (member_id, nearby_quiz_notification_enabled, dark_mode_enabled, version)
				VALUES (?, ?, ?, ?)
				""", memberId, nearbyQuizNotificationEnabled, darkModeEnabled, version);
		return new AuthenticatedMember(memberId, accessTokenService.issue(memberId, sessionId));
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

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
