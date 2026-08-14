package com.buyeoon.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
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
class NotificationReadIntegrationTests {

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
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("본인 소유 알림을 읽음 처리하면 갱신된 알림을 모든 필드와 함께 반환한다")
	void marksOwnNotificationAsReadAndReturnsUpdatedNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant occurredAt = Instant.parse("2026-08-10T00:00:00Z");
		UUID targetId = UUID.randomUUID();
		UUID notificationId = insertNotification(member.memberId(), "POINT", "포인트 적립", "10P 적립되었습니다", occurredAt, null,
				"point", targetId);

		MvcResult result = performRead(member, notificationId, "{\"read\":true}").andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.notificationId").value(notificationId.toString()))
				.andExpect(jsonPath("$.data.type").value("POINT")).andExpect(jsonPath("$.data.title").value("포인트 적립"))
				.andExpect(jsonPath("$.data.body").value("10P 적립되었습니다")).andExpect(jsonPath("$.data.read").value(true))
				.andExpect(jsonPath("$.data.targetType").value("point"))
				.andExpect(jsonPath("$.data.targetId").value(targetId.toString())).andReturn();

		String content = result.getResponse().getContentAsString();
		assertThat(OffsetDateTime.parse(JsonPath.read(content, "$.data.occurredAt")).toInstant()).isEqualTo(occurredAt);
		assertThat(readAt(notificationId)).isNotNull();
	}

	@Test
	@DisplayName("이미 읽은 알림을 다시 읽음 처리해도 읽은 시각과 상태가 유지된다")
	void rereadingReadNotificationIsIdempotent() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant readAt = Instant.parse("2026-08-09T00:00:00Z");
		UUID notificationId = insertNotification(member.memberId(), "BADGE", "배지 획득", "새 배지를 획득했습니다",
				Instant.parse("2026-08-10T00:00:00Z"), readAt, null, null);

		performRead(member, notificationId, "{\"read\":true}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.read").value(true));

		assertThat(readAt(notificationId)).isEqualTo(readAt);
	}

	@Test
	@DisplayName("본인 소유가 아니거나 존재하지 않는 알림 ID는 404 RESOURCE_NOT_FOUND다")
	void returnsNotFoundForForeignOrMissingNotification() throws Exception {
		AuthenticatedMember me = insertAuthenticatedMember();
		AuthenticatedMember other = insertAuthenticatedMember();
		UUID foreignNotification = insertNotification(other.memberId(), "POINT", "타인 알림", "b", Instant.now(), null,
				null, null);

		performRead(me, foreignNotification, "{\"read\":true}").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
		performRead(me, UUID.randomUUID(), "{\"read\":true}").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("read 필드 누락, false, 잘못된 UUID 형식은 400 INVALID_REQUEST다")
	void rejectsInvalidRequests() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID notificationId = insertNotification(member.memberId(), "POINT", "제목", "본문", Instant.now(), null, null,
				null);

		for (String invalidBody : List.of("{}", "{\"read\":false}", "{\"read\":null}")) {
			performRead(member, notificationId, invalidBody).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		}
		for (String invalidId : List.of("not-a-uuid", "1-1-1-1-1", "12345678123412341234123456789012")) {
			performRead(member, invalidId, "{\"read\":true}").andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		}

		assertThat(readAt(notificationId)).isNull();
	}

	@Test
	@DisplayName("인증되지 않은 요청은 401 UNAUTHORIZED다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(patch("/members/me/notifications/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"read\":true}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private org.springframework.test.web.servlet.ResultActions performRead(AuthenticatedMember member, Object pathId,
			String body) throws Exception {
		return mockMvc.perform(patch("/members/me/notifications/" + pathId).header("Authorization", bearer(member))
				.contentType(MediaType.APPLICATION_JSON).content(body));
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
		Timestamp readAtTimestamp = readAt == null ? null : Timestamp.from(readAt);
		jdbcTemplate.update("""
				INSERT INTO notifications (id, member_id, type, title, body, read_at, target_type, target_id,
				                          occurred_at)
				VALUES (?, ?, ?::notification_type, ?, ?, ?, ?, ?, ?)
				""", id, memberId, type, title, body, readAtTimestamp, targetType, targetId,
				Timestamp.from(occurredAt));
		return id;
	}

	private Instant readAt(UUID notificationId) {
		List<Timestamp> rows = jdbcTemplate.query("SELECT read_at FROM notifications WHERE id = ?",
				(resultSet, rowNumber) -> resultSet.getTimestamp("read_at"), notificationId);
		return rows.isEmpty() || rows.getFirst() == null ? null : rows.getFirst().toInstant();
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
