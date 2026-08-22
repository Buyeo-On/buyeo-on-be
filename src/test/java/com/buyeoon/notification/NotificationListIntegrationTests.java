package com.buyeoon.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class NotificationListIntegrationTests {

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

	@BeforeAll
	static void configureAwsCredentials() {
		System.setProperty("aws.accessKeyId", "test-access-key");
		System.setProperty("aws.secretAccessKey", "test-secret-key");
	}

	@AfterAll
	static void clearAwsCredentials() {
		System.clearProperty("aws.accessKeyId");
		System.clearProperty("aws.secretAccessKey");
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("본인의 알림을 발생 시각 내림차순으로 모든 필드와 함께 반환한다")
	void returnsOwnNotificationsDescendingWithAllFields() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant base = Instant.parse("2026-08-10T00:00:00Z");
		UUID older = insertNotification(member.memberId(), "POINT", "포인트 적립", "10P 적립되었습니다", base, null, null, null);
		UUID newer = insertNotification(member.memberId(), "BADGE", "배지 획득", "새 배지를 획득했습니다", base.plusSeconds(60),
				base.plusSeconds(120), "badge", UUID.randomUUID());

		mockMvc.perform(get("/members/me/notifications").header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.items", hasSize(2)))
				.andExpect(jsonPath("$.data.items[0].notificationId").value(newer.toString()))
				.andExpect(jsonPath("$.data.items[0].type").value("BADGE"))
				.andExpect(jsonPath("$.data.items[0].title").value("배지 획득"))
				.andExpect(jsonPath("$.data.items[0].body").value("새 배지를 획득했습니다"))
				.andExpect(jsonPath("$.data.items[0].read").value(true))
				.andExpect(jsonPath("$.data.items[0].occurredAt").value("2026-08-10T09:01:00+09:00"))
				.andExpect(jsonPath("$.data.items[0].targetType").value("badge"))
				.andExpect(jsonPath("$.data.items[0].targetId").exists())
				.andExpect(jsonPath("$.data.items[1].notificationId").value(older.toString()))
				.andExpect(jsonPath("$.data.items[1].read").value(false))
				.andExpect(jsonPath("$.data.items[1].occurredAt").value("2026-08-10T09:00:00+09:00"))
				.andExpect(jsonPath("$.data.items[1].targetType").doesNotExist())
				.andExpect(jsonPath("$.data.page.hasNext").value(false))
				.andExpect(jsonPath("$.data.page.nextCursor").doesNotExist());
	}

	@Test
	@DisplayName("cursor로 다음 페이지를 이어서 조회하고 hasNext를 정확히 계산한다")
	void paginatesWithCursorAndComputesHasNextAccurately() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant base = Instant.parse("2026-08-10T00:00:00Z");
		List<UUID> ids = List.of(insertNotification(member.memberId(), "POINT", "1", "1", base, null, null, null),
				insertNotification(member.memberId(), "POINT", "2", "2", base.plusSeconds(1), null, null, null),
				insertNotification(member.memberId(), "POINT", "3", "3", base.plusSeconds(2), null, null, null),
				insertNotification(member.memberId(), "POINT", "4", "4", base.plusSeconds(3), null, null, null),
				insertNotification(member.memberId(), "POINT", "5", "5", base.plusSeconds(4), null, null, null));
		List<String> expectedDescending = List.of(ids.get(4).toString(), ids.get(3).toString(), ids.get(2).toString(),
				ids.get(1).toString(), ids.get(0).toString());

		MvcResult firstPage = mockMvc
				.perform(get("/members/me/notifications").param("size", "2").header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(2)))
				.andExpect(jsonPath("$.data.items[0].notificationId").value(expectedDescending.get(0)))
				.andExpect(jsonPath("$.data.items[1].notificationId").value(expectedDescending.get(1)))
				.andExpect(jsonPath("$.data.page.hasNext").value(true))
				.andExpect(jsonPath("$.data.page.nextCursor").exists()).andReturn();
		String firstCursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.data.page.nextCursor");

		MvcResult secondPage = mockMvc
				.perform(get("/members/me/notifications").param("size", "2").param("cursor", firstCursor)
						.header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(2)))
				.andExpect(jsonPath("$.data.items[0].notificationId").value(expectedDescending.get(2)))
				.andExpect(jsonPath("$.data.items[1].notificationId").value(expectedDescending.get(3)))
				.andExpect(jsonPath("$.data.page.hasNext").value(true))
				.andExpect(jsonPath("$.data.page.nextCursor").exists()).andReturn();
		String secondCursor = JsonPath.read(secondPage.getResponse().getContentAsString(), "$.data.page.nextCursor");

		mockMvc.perform(get("/members/me/notifications").param("size", "2").param("cursor", secondCursor)
				.header("Authorization", bearer(member))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items", hasSize(1)))
				.andExpect(jsonPath("$.data.items[0].notificationId").value(expectedDescending.get(4)))
				.andExpect(jsonPath("$.data.page.hasNext").value(false))
				.andExpect(jsonPath("$.data.page.nextCursor").doesNotExist());
	}

	@Test
	@DisplayName("size는 기본값 20이며 1~100을 벗어나거나 정수가 아니면 400 INVALID_REQUEST다")
	void validatesSizeRange() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		for (int i = 0; i < 21; i++) {
			insertNotification(member.memberId(), "POINT", "n" + i, "b", Instant.now().plusSeconds(i), null, null,
					null);
		}

		mockMvc.perform(get("/members/me/notifications").header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(20)));

		for (String invalidSize : List.of("0", "101", "abc", "-1")) {
			mockMvc.perform(
					get("/members/me/notifications").param("size", invalidSize).header("Authorization", bearer(member)))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		}
	}

	@Test
	@DisplayName("잘못된 형식의 cursor는 400 INVALID_REQUEST다")
	void validatesCursorFormat() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		for (String invalidCursor : List.of("not-base64!!", "abc", "MTIz")) {
			mockMvc.perform(get("/members/me/notifications").param("cursor", invalidCursor).header("Authorization",
					bearer(member))).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		}
	}

	@Test
	@DisplayName("알림이 없으면 빈 목록과 hasNext false를 반환한다")
	void returnsEmptyListWhenNoNotifications() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		mockMvc.perform(get("/members/me/notifications").header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(0)))
				.andExpect(jsonPath("$.data.page.hasNext").value(false));
	}

	@Test
	@DisplayName("다른 회원의 알림은 결과에 포함되지 않는다")
	void excludesOtherMembersNotifications() throws Exception {
		AuthenticatedMember me = insertAuthenticatedMember();
		AuthenticatedMember other = insertAuthenticatedMember();
		insertNotification(other.memberId(), "POINT", "타인 알림", "b", Instant.now(), null, null, null);
		UUID mine = insertNotification(me.memberId(), "POINT", "내 알림", "b", Instant.now(), null, null, null);

		mockMvc.perform(get("/members/me/notifications").header("Authorization", bearer(me))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items", hasSize(1)))
				.andExpect(jsonPath("$.data.items[0].notificationId").value(mine.toString()));
	}

	@Test
	@DisplayName("인증되지 않은 요청은 401 UNAUTHORIZED다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/members/me/notifications")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("반복 조회해도 DB 상태가 변하지 않는다")
	void repeatedCallsDoNotChangeDbState() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertNotification(member.memberId(), "POINT", "제목", "본문", Instant.now(), null, null, null);
		List<Map<String, Object>> before = notifications();

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/members/me/notifications").header("Authorization", bearer(member)))
					.andExpect(status().isOk());
		}

		assertThat(notifications()).isEqualTo(before);
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

	private UUID insertNotification(UUID memberId, String type, String title, String body, Instant occurredAt,
			Instant readAt, String targetType, UUID targetId) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO notifications
				    (id, member_id, type, title, body, read_at, target_type, target_id, occurred_at)
				VALUES (?, ?, ?::notification_type, ?, ?, ?, ?, ?, ?)
				""", id, memberId, type, title, body, readAt == null ? null : Timestamp.from(readAt), targetType,
				targetId, Timestamp.from(occurredAt));
		return id;
	}

	private List<Map<String, Object>> notifications() {
		return jdbcTemplate.queryForList("""
				SELECT id, member_id, type::text, title, body, read_at, target_type, target_id, occurred_at
				FROM notifications
				ORDER BY id
				""");
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> APPLICATION_USERNAME);
		registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
		registry.add("storage.images.bucket", () -> "buyeoon-test-images");
		registry.add("storage.images.region", () -> "ap-northeast-2");
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	private record AuthenticatedMember(UUID memberId, UUID sessionId, String accessToken) {
	}
}
