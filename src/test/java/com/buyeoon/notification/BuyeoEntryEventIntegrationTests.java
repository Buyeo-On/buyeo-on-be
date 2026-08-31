package com.buyeoon.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * UC-27 부여 진입 알림의 공개 seam인 {@code POST /notifications/buyeo-entry-events}를
 * 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BuyeoEntryEventIntegrationTests {

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

	@BeforeEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 경계 안 위치는 알림을 생성하고 발송 여부 true를 반환한다. */
	@Test
	@DisplayName("경계 안 위치는 알림을 생성한다")
	void insideBoundaryCreatesNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		performNotify(member, "entry-key-01", request(36.27, 126.91)).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.notificationSent").value(true));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'BUYEO_ENTRY'", Long.class,
				member.memberId())).isEqualTo(1L);
	}

	/** 경계 밖 위치는 알림을 생성하지 않지만 요청 자체는 성공으로 처리한다. */
	@Test
	@DisplayName("경계 밖 위치는 알림을 생성하지 않는다")
	void outsideBoundaryDoesNotCreateNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		performNotify(member, "entry-key-02", request(36.5, 127.2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertNoBuyeoEntryNotification(member.memberId());
	}

	/** 진행 중인 여행이 있으면 이미 여행 가능 상태를 알고 있으므로 알림을 생성하지 않는다. */
	@Test
	@DisplayName("진행 중인 여행이 있으면 알림을 생성하지 않는다")
	void inProgressTripSkipsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);

		performNotify(member, "entry-key-03", request(36.27, 126.91)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertNoBuyeoEntryNotification(member.memberId());
	}

	/** 종료했지만 정산하지 않은 여행이 있으면 알림을 생성하지 않는다. */
	@Test
	@DisplayName("미정산 여행이 있으면 알림을 생성하지 않는다")
	void unsettledEndedTripSkipsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant endedAt = Instant.parse("2026-08-12T09:00:00Z");
		insertTrip(member.memberId(), "ENDED", endedAt.minus(1, ChronoUnit.HOURS), endedAt);

		performNotify(member, "entry-key-04", request(36.27, 126.91)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertNoBuyeoEntryNotification(member.memberId());
	}

	/** 정산까지 끝낸 여행은 새로운 부여 진입 알림을 막지 않는다. */
	@Test
	@DisplayName("정산 완료된 여행은 알림 생성을 막지 않는다")
	void settledTripAllowsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant endedAt = Instant.parse("2026-08-12T09:00:00Z");
		insertTrip(member.memberId(), "SETTLED", endedAt.minus(1, ChronoUnit.HOURS), endedAt);

		performNotify(member, "entry-key-05", request(36.27, 126.91)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(true));
	}

	/** 마지막 발송 후 12시간이 지나지 않았으면 재발송하지 않는다. */
	@Test
	@DisplayName("쿨다운 12시간 이내에는 재발송하지 않는다")
	void withinCooldownSkipsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertBuyeoEntryNotification(member.memberId(), Instant.now().minus(11, ChronoUnit.HOURS));

		performNotify(member, "entry-key-06", request(36.27, 126.91)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'BUYEO_ENTRY'", Long.class,
				member.memberId())).isEqualTo(1L);
	}

	/** 쿨다운 12시간이 지나면 다시 발송할 수 있다. */
	@Test
	@DisplayName("쿨다운 12시간이 지나면 재발송한다")
	void afterCooldownAllowsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertBuyeoEntryNotification(member.memberId(), Instant.now().minus(13, ChronoUnit.HOURS));

		performNotify(member, "entry-key-07", request(36.27, 126.91)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(true));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'BUYEO_ENTRY'", Long.class,
				member.memberId())).isEqualTo(2L);
	}

	/** 같은 키와 같은 본문의 재요청은 최초 처리 결과를 그대로 반환하며 알림을 다시 만들지 않는다. */
	@Test
	@DisplayName("동일한 멱등성 요청은 최초 응답을 재사용한다")
	void sameIdempotentRequestReturnsFirstResponse() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		String key = "retry-entry-key";

		String first = performNotify(member, key, request(36.27, 126.91)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String retried = performNotify(member, key, request(36.27, 126.91)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

		assertThat(retried).isEqualTo(first);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'BUYEO_ENTRY'", Long.class,
				member.memberId())).isEqualTo(1L);
	}

	/** 같은 키를 다른 요청 본문에 재사용하면 충돌을 반환한다. */
	@Test
	@DisplayName("멱등성 키를 다른 본문에 재사용하면 충돌한다")
	void reusedKeyWithDifferentBodyConflicts() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		performNotify(member, "conflict-entry-key", request(36.27, 126.91)).andExpect(status().isOk());

		performNotify(member, "conflict-entry-key", request(36.2, 126.8)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	/** 인증되지 않은 요청은 부여 진입 알림 계층에 도달하지 않는다. */
	@Test
	@DisplayName("부여 진입 알림에는 유효한 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(post("/notifications/buyeo-entry-events").header("Idempotency-Key", "unauth-entry-key")
				.contentType(MediaType.APPLICATION_JSON).content(request(36.27, 126.91)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 알 수 없는 필드가 섞인 요청은 400 INVALID_REQUEST다. */
	@Test
	@DisplayName("잘못된 요청 형식은 400을 반환한다")
	void invalidRequestBodyIsRejected() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		performNotify(member, "invalid-entry-key", "{\"location\":{\"latitude\":36.27}}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	private ResultActions performNotify(AuthenticatedMember member, String key, String body) throws Exception {
		var request = post("/notifications/buyeo-entry-events")
				.header("Authorization", "Bearer " + member.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content(body);
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mockMvc.perform(request);
	}

	private AuthenticatedMember insertAuthenticatedMember() {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO member_settings
				    (member_id, nearby_quiz_notification_enabled, dark_mode_enabled, version)
				VALUES (?, true, false, 0)
				""", memberId);
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
		return new AuthenticatedMember(memberId, accessTokenService.issue(memberId, sessionId));
	}

	private void insertTrip(UUID memberId, String status, Instant startedAt, Instant endedAt) {
		Instant started = startedAt == null ? Instant.now().minus(1, ChronoUnit.HOURS) : startedAt;
		Timestamp settledAt = "SETTLED".equals(status) && endedAt != null
				? Timestamp.from(endedAt.plus(1, ChronoUnit.HOURS))
				: null;
		jdbcTemplate.update("""
				INSERT INTO trips (id, member_id, status, started_at, ended_at, settled_at)
				VALUES (?, ?, ?::trip_status, ?, ?, ?)
				""", UUID.randomUUID(), memberId, status, Timestamp.from(started),
				endedAt == null ? null : Timestamp.from(endedAt), settledAt);
	}

	private void insertBuyeoEntryNotification(UUID memberId, Instant occurredAt) {
		jdbcTemplate.update("""
				INSERT INTO notifications (id, member_id, type, title, body, occurred_at)
				VALUES (?, ?, 'BUYEO_ENTRY', '부여에 도착했어요!', '지금 바로 여행을 시작해보세요.', ?)
				""", UUID.randomUUID(), memberId, Timestamp.from(occurredAt));
	}

	private void assertNoBuyeoEntryNotification(UUID memberId) {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'BUYEO_ENTRY'", Long.class,
				memberId)).isZero();
	}

	private String request(double latitude, double longitude) {
		return "{\"location\":{\"latitude\":" + latitude + ",\"longitude\":" + longitude
				+ ",\"accuracyMeters\":5.5,\"capturedAt\":\"2026-08-12T15:30:00+09:00\"}}";
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
		registry.add("location.buyeo-boundary", () -> "classpath:boundaries/buyeo-test.geojson");
	}

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
