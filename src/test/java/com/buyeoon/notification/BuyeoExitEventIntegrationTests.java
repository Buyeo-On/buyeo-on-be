package com.buyeoon.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.push.FcmClient;
import com.buyeoon.notification.push.FcmSendResult;
import com.buyeoon.notification.push.PushMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * UC-28 부여 이탈 알림의 공개 seam인 {@code POST /notifications/buyeo-exit-events}를 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BuyeoExitEventIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	private static final String EXIT_TITLE = "부여를 떠났어요!";
	private static final String EXIT_BODY = "오늘의 여행을 마무리해보세요.";

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
	private FakeFcmClient fakeFcmClient;

	@BeforeEach
	void resetFakeClient() {
		fakeFcmClient.reset();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM push_tokens");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 경계 밖 위치와 진행 중 여행이 있으면 알림을 만들고 FCM을 발송한다. */
	@Test
	@DisplayName("경계 밖이고 진행 중인 여행이 있으면 알림을 생성하고 FCM을 발송한다")
	void outsideBoundaryWithActiveTripCreatesNotificationAndSendsPush() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, "exit-token");
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);
		fakeFcmClient.expectInvocations(1);

		performNotify(member, "exit-key-01", request(36.5, 127.2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.notificationSent").value(true));

		assertThat(countBuyeoExit(member.memberId())).isEqualTo(1L);
		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		assertThat(fakeFcmClient.invocations()).singleElement().satisfies(invocation -> {
			assertThat(invocation.tokens()).containsExactly("exit-token");
			assertThat(invocation.message().type()).isEqualTo(NotificationType.BUYEO_EXIT);
			assertThat(invocation.message().title()).isEqualTo(EXIT_TITLE);
			assertThat(invocation.message().body()).isEqualTo(EXIT_BODY);
		});
	}

	/** 제출 위치가 부여 경계 안이면 이탈로 보지 않는다. */
	@Test
	@DisplayName("경계 안 위치는 알림을 생성하지 않는다")
	void insideBoundaryDoesNotCreateNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, "unused-token");
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);
		fakeFcmClient.expectInvocations(1);

		performNotify(member, "exit-key-02", request(36.27, 126.91)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertThat(countBuyeoExit(member.memberId())).isZero();
		assertThat(fakeFcmClient.awaitInvocations(1, TimeUnit.SECONDS)).isFalse();
	}

	/** 안내할 진행 중 여행이 없으면 알림을 만들지 않는다. */
	@Test
	@DisplayName("진행 중인 여행이 없으면 알림을 생성하지 않는다")
	void noInProgressTripSkipsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, "unused-token");

		performNotify(member, "exit-key-03", request(36.5, 127.2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertThat(countBuyeoExit(member.memberId())).isZero();
	}

	/** 이미 종료된 여행은 진행 중이 아니므로 이탈 알림 대상이 아니다. */
	@Test
	@DisplayName("종료된 여행만 있으면 알림을 생성하지 않는다")
	void endedTripSkipsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, "unused-token");
		Instant endedAt = Instant.parse("2026-08-12T09:00:00Z");
		insertTrip(member.memberId(), "ENDED", endedAt.minus(1, ChronoUnit.HOURS), endedAt);

		performNotify(member, "exit-key-04", request(36.5, 127.2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertThat(countBuyeoExit(member.memberId())).isZero();
	}

	/** 정산까지 끝난 여행도 진행 중이 아니므로 이탈 알림을 만들지 않는다. */
	@Test
	@DisplayName("정산 완료된 여행만 있으면 알림을 생성하지 않는다")
	void settledTripSkipsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, "unused-token");
		Instant endedAt = Instant.parse("2026-08-12T09:00:00Z");
		insertTrip(member.memberId(), "SETTLED", endedAt.minus(1, ChronoUnit.HOURS), endedAt);

		performNotify(member, "exit-key-05", request(36.5, 127.2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertThat(countBuyeoExit(member.memberId())).isZero();
	}

	/** 마지막 발송 후 12시간이 지나지 않았으면 재발송하지 않는다. */
	@Test
	@DisplayName("쿨다운 12시간 이내에는 재발송하지 않는다")
	void withinCooldownSkipsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, "unused-token");
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);
		insertBuyeoExitNotification(member.memberId(), Instant.now().minus(11, ChronoUnit.HOURS));

		performNotify(member, "exit-key-06", request(36.5, 127.2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(false));

		assertThat(countBuyeoExit(member.memberId())).isEqualTo(1L);
	}

	/** 쿨다운 12시간이 지나면 다시 발송할 수 있다. */
	@Test
	@DisplayName("쿨다운 12시간이 지나면 재발송한다")
	void afterCooldownAllowsNotification() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, "exit-token");
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);
		insertBuyeoExitNotification(member.memberId(), Instant.now().minus(13, ChronoUnit.HOURS));
		fakeFcmClient.expectInvocations(1);

		performNotify(member, "exit-key-07", request(36.5, 127.2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(true));

		assertThat(countBuyeoExit(member.memberId())).isEqualTo(2L);
		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
	}

	/** 같은 키와 같은 본문의 재요청은 최초 처리 결과를 그대로 반환하며 알림을 다시 만들지 않는다. */
	@Test
	@DisplayName("동일한 멱등성 요청은 최초 응답을 재사용한다")
	void sameIdempotentRequestReturnsFirstResponse() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, "exit-token");
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);
		String key = "retry-exit-key";
		fakeFcmClient.expectInvocations(1);

		String first = performNotify(member, key, request(36.5, 127.2)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		fakeFcmClient.reset();
		fakeFcmClient.expectInvocations(1);

		String retried = performNotify(member, key, request(36.5, 127.2)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

		assertThat(retried).isEqualTo(first);
		assertThat(countBuyeoExit(member.memberId())).isEqualTo(1L);
		assertThat(fakeFcmClient.awaitInvocations(1, TimeUnit.SECONDS)).isFalse();
	}

	/** 같은 키를 다른 요청 본문에 재사용하면 충돌을 반환한다. */
	@Test
	@DisplayName("멱등성 키를 다른 본문에 재사용하면 충돌한다")
	void reusedKeyWithDifferentBodyConflicts() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, "unused-token");
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);
		performNotify(member, "conflict-exit-key", request(36.5, 127.2)).andExpect(status().isOk());

		performNotify(member, "conflict-exit-key", request(36.6, 127.3)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	/** 인증되지 않은 요청은 부여 이탈 알림 계층에 도달하지 않는다. */
	@Test
	@DisplayName("부여 이탈 알림에는 유효한 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(post("/notifications/buyeo-exit-events").header("Idempotency-Key", "unauth-exit-key")
				.contentType(MediaType.APPLICATION_JSON).content(request(36.5, 127.2)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 알림 동의가 꺼져 있으면 persistent 알림만 남기고 FCM은 보내지 않는다. */
	@Test
	@DisplayName("알림 동의가 꺼져 있으면 알림은 생성하고 FCM은 발송하지 않는다")
	void disabledConsentCreatesNotificationWithoutPush() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(false, "consent-off-token");
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);
		fakeFcmClient.expectInvocations(1);

		performNotify(member, "exit-key-consent", request(36.5, 127.2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(true));

		assertThat(countBuyeoExit(member.memberId())).isEqualTo(1L);
		assertThat(fakeFcmClient.awaitInvocations(1, TimeUnit.SECONDS)).isFalse();
	}

	/** 유효한 푸시 토큰이 없으면 persistent 알림만 남기고 FCM은 보내지 않는다. */
	@Test
	@DisplayName("푸시 토큰이 없으면 알림은 생성하고 FCM은 발송하지 않는다")
	void missingPushTokenCreatesNotificationWithoutPush() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, null);
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);
		fakeFcmClient.expectInvocations(1);

		performNotify(member, "exit-key-no-token", request(36.5, 127.2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationSent").value(true));

		assertThat(countBuyeoExit(member.memberId())).isEqualTo(1L);
		assertThat(fakeFcmClient.awaitInvocations(1, TimeUnit.SECONDS)).isFalse();
	}

	/** 알 수 없는 필드가 섞인 요청은 400 INVALID_REQUEST다. */
	@Test
	@DisplayName("잘못된 요청 형식은 400을 반환한다")
	void invalidRequestBodyIsRejected() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember(true, null);
		insertTrip(member.memberId(), "IN_PROGRESS", null, null);

		performNotify(member, "invalid-exit-key", "{\"location\":{\"latitude\":36.5}}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
	}

	/** 인증된 회원의 이탈 알림 요청을 보낸다. */
	private ResultActions performNotify(AuthenticatedMember member, String key, String body) throws Exception {
		var request = post("/notifications/buyeo-exit-events").header("Authorization", "Bearer " + member.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content(body);
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mockMvc.perform(request);
	}

	/** 동의 여부와 선택적 푸시 토큰을 가진 로그인 회원을 만든다. */
	private AuthenticatedMember insertAuthenticatedMember(boolean notificationEnabled, String registrationToken) {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO member_settings
				    (member_id, nearby_quiz_notification_enabled, dark_mode_enabled, version)
				VALUES (?, ?, false, 0)
				""", memberId, notificationEnabled);
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
		if (registrationToken != null) {
			jdbcTemplate.update("""
					INSERT INTO push_tokens (auth_session_id, registration_token)
					VALUES (?, ?)
					""", sessionId, registrationToken);
		}
		return new AuthenticatedMember(memberId, accessTokenService.issue(memberId, sessionId));
	}

	/** 지정한 상태의 여행을 한 건 넣는다. */
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

	/** 쿨다운 판정용으로 이전 이탈 알림을 넣는다. */
	private void insertBuyeoExitNotification(UUID memberId, Instant occurredAt) {
		jdbcTemplate.update("""
				INSERT INTO notifications (id, member_id, type, title, body, occurred_at)
				VALUES (?, ?, 'BUYEO_EXIT'::notification_type, ?, ?, ?)
				""", UUID.randomUUID(), memberId, EXIT_TITLE, EXIT_BODY, Timestamp.from(occurredAt));
	}

	/** 회원의 BUYEO_EXIT 알림 건수를 센다. */
	private long countBuyeoExit(UUID memberId) {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE member_id = ? AND type = 'BUYEO_EXIT'", Long.class, memberId);
	}

	/** 위치 제출 본문을 만든다. */
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

	@TestConfiguration
	static class FakeFcmClientConfiguration {

		@Bean
		@Primary
		FakeFcmClient fakeFcmClient() {
			return new FakeFcmClient();
		}
	}

	/** 실제 FCM 대신 발송 요청을 기록하는 테스트 대역이다. */
	static class FakeFcmClient implements FcmClient {

		private final List<Invocation> invocations = new CopyOnWriteArrayList<>();
		private final AtomicInteger callCount = new AtomicInteger();
		private volatile CountDownLatch latch = new CountDownLatch(0);
		private volatile FcmResponder responder = (tokens, message) -> new FcmSendResult(tokens.size(), 0, List.of());

		@Override
		public FcmSendResult sendMulticast(List<String> registrationTokens, PushMessage message) {
			callCount.incrementAndGet();
			try {
				FcmSendResult result = responder.respond(registrationTokens, message);
				String requestId = MDC.get("request_id");
				invocations.add(new Invocation(List.copyOf(registrationTokens), message, result, requestId));
				return result;
			} finally {
				latch.countDown();
			}
		}

		void reset() {
			invocations.clear();
			callCount.set(0);
			latch = new CountDownLatch(0);
			responder = (tokens, message) -> new FcmSendResult(tokens.size(), 0, List.of());
		}

		void expectInvocations(int count) {
			latch = new CountDownLatch(count);
		}

		boolean awaitInvocations(long timeout, TimeUnit unit) throws InterruptedException {
			return latch.await(timeout, unit);
		}

		List<Invocation> invocations() {
			return List.copyOf(invocations);
		}

		interface FcmResponder {

			FcmSendResult respond(List<String> tokens, PushMessage message);
		}

		record Invocation(List<String> tokens, PushMessage message, FcmSendResult result, String mdcRequestId) {
		}
	}
}
