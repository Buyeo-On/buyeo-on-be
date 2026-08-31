package com.buyeoon.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.push.FcmClient;
import com.buyeoon.notification.push.FcmSendResult;
import com.buyeoon.notification.push.PushMessage;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 PostgreSQL과 fake FCM client를 사용해 {@link PushNotificationPublisher}의 공개
 * seam을 검증한다.
 */
@SpringBootTest
@Testcontainers
class PushNotificationPublisherIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private PushNotificationPublisher publisher;

	@Autowired
	private FakeFcmClient fakeFcmClient;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private Executor pushNotificationExecutor;

	@BeforeEach
	void resetFakeClient() {
		fakeFcmClient.reset();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM push_tokens");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("트랜잭션이 commit되면 비동기 발송이 시작된다")
	void dispatchesAfterCommit() throws Exception {
		UUID memberId = insertActiveMemberWithToken("committed-token");
		fakeFcmClient.expectInvocations(1);
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);

		transactions.executeWithoutResult(
				status -> publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null));

		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		assertThat(fakeFcmClient.invocations()).singleElement()
				.satisfies(invocation -> assertThat(invocation.tokens()).containsExactly("committed-token"));
	}

	@Test
	@DisplayName("트랜잭션이 rollback되면 발송하지 않는다")
	void doesNotDispatchAfterRollback() throws Exception {
		UUID memberId = insertActiveMemberWithToken("rolled-back-token");
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);
		fakeFcmClient.expectInvocations(1);

		transactions.executeWithoutResult(status -> {
			publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);
			status.setRollbackOnly();
		});

		assertThat(fakeFcmClient.awaitInvocations(1, TimeUnit.SECONDS)).isFalse();
		assertThat(fakeFcmClient.invocations()).isEmpty();
	}

	@Test
	@DisplayName("트랜잭션 밖의 독립 요청도 같은 executor에서 비동기로 처리된다")
	void dispatchesImmediatelyOutsideTransaction() throws Exception {
		UUID memberId = insertActiveMemberWithToken("standalone-token");
		fakeFcmClient.expectInvocations(1);

		publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);

		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		assertThat(fakeFcmClient.invocations()).singleElement()
				.satisfies(invocation -> assertThat(invocation.tokens()).containsExactly("standalone-token"));
	}

	@Test
	@DisplayName("발송을 요청한 스레드의 MDC가 워커 스레드로 전파된다")
	void propagatesMdcToWorkerThread() throws Exception {
		UUID memberId = insertActiveMemberWithToken("mdc-token");
		fakeFcmClient.expectInvocations(1);

		MDC.put("request_id", "req-123");
		try {
			publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);
		} finally {
			MDC.remove("request_id");
		}

		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		assertThat(fakeFcmClient.invocations()).singleElement()
				.satisfies(invocation -> assertThat(invocation.mdcRequestId()).isEqualTo("req-123"));
	}

	@Test
	@DisplayName("한 회원의 모든 활성 기기 토큰이 발송 대상이 되며 notification ID가 없는 요청도 처리된다")
	void targetsAllActiveTokensWithoutNotificationId() throws Exception {
		UUID memberId = insertActiveMember();
		insertSessionWithToken(memberId, "device-1", null, 30);
		insertSessionWithToken(memberId, "device-2", null, 30);
		insertSessionWithToken(memberId, "revoked", Instant.now(), 30);
		fakeFcmClient.expectInvocations(1);

		publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);

		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		assertThat(fakeFcmClient.invocations()).singleElement().satisfies(
				invocation -> assertThat(invocation.tokens()).containsExactlyInAnyOrder("device-1", "device-2"));
	}

	@Test
	@DisplayName("제목·본문·유형과 notification ID·target 정보가 그대로 전달된다")
	void deliversPayloadFieldsExactly() throws Exception {
		UUID memberId = insertActiveMemberWithToken("payload-token");
		UUID notificationId = UUID.randomUUID();
		UUID targetId = UUID.randomUUID();
		fakeFcmClient.expectInvocations(1);

		publisher.publish(memberId, NotificationType.BADGE, "새로운 배지를 획득했어요!", "탐험가 배지를 획득했어요.", notificationId, "BADGE",
				targetId);

		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		PushMessage message = fakeFcmClient.invocations().get(0).message();
		assertThat(message.type()).isEqualTo(NotificationType.BADGE);
		assertThat(message.title()).isEqualTo("새로운 배지를 획득했어요!");
		assertThat(message.body()).isEqualTo("탐험가 배지를 획득했어요.");
		assertThat(message.notificationId()).isEqualTo(notificationId);
		assertThat(message.targetType()).isEqualTo("BADGE");
		assertThat(message.targetId()).isEqualTo(targetId);
	}

	@Test
	@DisplayName("대상이 500개를 초과하면 최대 500개 단위로 나뉘어 모든 토큰이 한 번씩 요청된다")
	void splitsIntoBatchesOfAtMost500Tokens() throws Exception {
		UUID memberId = insertActiveMember();
		List<String> expectedTokens = new ArrayList<>();
		for (int i = 0; i < 750; i++) {
			String token = "token-" + i;
			expectedTokens.add(token);
			insertSessionWithToken(memberId, token, null, 30);
		}
		fakeFcmClient.expectInvocations(2);

		publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);

		assertThat(fakeFcmClient.awaitInvocations(10, TimeUnit.SECONDS)).isTrue();
		List<FakeFcmClient.Invocation> invocations = fakeFcmClient.invocations();
		assertThat(invocations).hasSize(2);
		assertThat(invocations)
				.allSatisfy(invocation -> assertThat(invocation.tokens().size()).isLessThanOrEqualTo(500));
		List<String> allDeliveredTokens = new ArrayList<>();
		invocations.forEach(invocation -> allDeliveredTokens.addAll(invocation.tokens()));
		assertThat(allDeliveredTokens).containsExactlyInAnyOrderElementsOf(expectedTokens);
	}

	@Test
	@DisplayName("부분 실패에서 UNREGISTERED가 반환된 등록 토큰만 삭제된다")
	void deletesOnlyUnregisteredTokens() throws Exception {
		UUID memberId = insertActiveMember();
		insertSessionWithToken(memberId, "unregistered-token", null, 30);
		insertSessionWithToken(memberId, "kept-token", null, 30);
		fakeFcmClient.expectInvocations(1);
		fakeFcmClient.respondWith((tokens, message) -> new FcmSendResult(1, 1, List.of("unregistered-token")));

		publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);

		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		awaitRemainingTokens(memberId, Set.of("kept-token"));
	}

	@Test
	@DisplayName("인증·일시 장애·내부 오류 등 UNREGISTERED 이외 오류가 반환된 등록 토큰은 유지된다")
	void keepsTokensWithNonUnregisteredErrors() throws Exception {
		UUID memberId = insertActiveMemberWithToken("temporarily-failed-token");
		fakeFcmClient.expectInvocations(1);
		fakeFcmClient.respondWith((tokens, message) -> new FcmSendResult(0, 1, List.of()));

		publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);

		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		awaitRemainingTokens(memberId, Set.of("temporarily-failed-token"));
	}

	@Test
	@DisplayName("FCM 예외는 애플리케이션에서 재시도하지 않는다")
	void doesNotRetryAfterFcmException() throws Exception {
		UUID memberId = insertActiveMemberWithToken("failing-token");
		fakeFcmClient.expectInvocations(1);
		fakeFcmClient.respondWith((tokens, message) -> {
			throw new RuntimeException("FCM 발송 실패");
		});

		publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);

		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(300);
		assertThat(fakeFcmClient.callCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("executor의 worker 2개와 queue 100이 모두 사용 중이면 새 발송 요청을 drop하고 호출 thread에서 실행하지 않는다")
	void dropsWhenExecutorSaturated() throws Exception {
		awaitExecutorIdle();
		CountDownLatch started = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(102);
		try {
			for (int i = 0; i < 2; i++) {
				submitBlockingTask(started, release, finished);
			}
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			for (int i = 0; i < 100; i++) {
				submitBlockingTask(started, release, finished);
			}
			double dropsBefore = meterRegistry.get("push_notification.queue_dropped").counter().count();
			UUID memberId = insertActiveMemberWithToken("saturated-token");

			publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);

			assertThat(meterRegistry.get("push_notification.queue_dropped").counter().count())
					.isEqualTo(dropsBefore + 1);
			assertThat(fakeFcmClient.callCount()).isZero();
		} finally {
			release.countDown();
			assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private void submitBlockingTask(CountDownLatch started, CountDownLatch release, CountDownLatch finished) {
		pushNotificationExecutor.execute(() -> {
			started.countDown();
			try {
				release.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} finally {
				finished.countDown();
			}
		});
	}

	private void awaitExecutorIdle() throws InterruptedException {
		ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) pushNotificationExecutor;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty()) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("이전 비동기 발송 작업이 종료되지 않았습니다.");
	}

	@Test
	@DisplayName("accepted, 실패, 무효 토큰 삭제 결과를 메트릭으로 구분할 수 있다")
	void recordsDistinctMetricsPerOutcome() throws Exception {
		UUID memberId = insertActiveMember();
		insertSessionWithToken(memberId, "accepted-token", null, 30);
		insertSessionWithToken(memberId, "unregistered-metric-token", null, 30);
		double acceptedBefore = meterRegistry.get("push_notification.accepted").counter().count();
		double failedBefore = meterRegistry.get("push_notification.failed").counter().count();
		double deletedBefore = meterRegistry.get("push_notification.invalid_token_deleted").counter().count();
		fakeFcmClient.expectInvocations(1);
		fakeFcmClient.respondWith((tokens, message) -> new FcmSendResult(1, 1, List.of("unregistered-metric-token")));

		publisher.publish(memberId, NotificationType.BADGE, "제목", "본문", null, null, null);

		assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
		awaitRemainingTokens(memberId, Set.of("accepted-token"));
		assertThat(meterRegistry.get("push_notification.accepted").counter().count()).isEqualTo(acceptedBefore + 1);
		assertThat(meterRegistry.get("push_notification.failed").counter().count()).isEqualTo(failedBefore + 1);
		assertThat(meterRegistry.get("push_notification.invalid_token_deleted").counter().count())
				.isEqualTo(deletedBefore + 1);
	}

	@Test
	@DisplayName("모든 성공·오류 경로의 로그에 등록 토큰, 회원 ID와 알림 제목·본문이 노출되지 않는다")
	void logsExcludeSensitiveFields() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(PushNotificationPublisher.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			UUID memberId = insertActiveMemberWithToken("sensitive-registration-token");
			fakeFcmClient.expectInvocations(1);
			fakeFcmClient
					.respondWith((tokens, message) -> new FcmSendResult(0, 1, List.of("sensitive-registration-token")));

			publisher.publish(memberId, NotificationType.BADGE, "민감한 제목", "민감한 본문", null, null, null);

			assertThat(fakeFcmClient.awaitInvocations(5, TimeUnit.SECONDS)).isTrue();
			awaitRemainingTokens(memberId, Set.of());
			List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
			assertThat(messages).isNotEmpty();
			String combined = String.join("\n", messages);
			assertThat(combined).doesNotContain("sensitive-registration-token").doesNotContain(memberId.toString())
					.doesNotContain("민감한 제목").doesNotContain("민감한 본문");
		} finally {
			logger.detachAppender(appender);
		}
	}

	private void awaitRemainingTokens(UUID memberId, Set<String> expectedTokens) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			List<String> remaining = jdbcTemplate.query("""
					SELECT push_token.registration_token
					FROM push_tokens push_token
					JOIN auth_sessions session ON session.id = push_token.auth_session_id
					WHERE session.member_id = ?
					""", (resultSet, rowNumber) -> resultSet.getString("registration_token"), memberId);
			if (Set.copyOf(remaining).equals(expectedTokens)) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("무효 토큰 삭제가 기대한 상태로 수렴하지 않았습니다.");
	}

	private UUID insertActiveMemberWithToken(String registrationToken) {
		UUID memberId = insertActiveMember();
		insertSessionWithToken(memberId, registrationToken, null, 30);
		return memberId;
	}

	private UUID insertActiveMember() {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO member_settings
				    (member_id, nearby_quiz_notification_enabled, dark_mode_enabled, version)
				VALUES (?, true, false, 0)
				""", memberId);
		return memberId;
	}

	private void insertSessionWithToken(UUID memberId, String registrationToken, Instant revokedAt,
			long expiresInDays) {
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at, revoked_at)
				VALUES (?, ?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(expiresInDays, ChronoUnit.DAYS)),
				revokedAt == null ? null : Timestamp.from(revokedAt));
		jdbcTemplate.update("""
				INSERT INTO push_tokens (auth_session_id, registration_token)
				VALUES (?, ?)
				""", sessionId, registrationToken);
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

	@TestConfiguration
	static class FakeFcmClientConfiguration {

		@Bean
		@Primary
		FakeFcmClient fakeFcmClient() {
			return new FakeFcmClient();
		}
	}

	/** 실제 FCM 대신 발송 요청을 기록하는 테스트 대역이다. 호출 결과는 {@link #respondWith}로 구성한다. */
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

		void respondWith(FcmResponder responder) {
			this.responder = responder;
		}

		boolean awaitInvocations(long timeout, TimeUnit unit) throws InterruptedException {
			return latch.await(timeout, unit);
		}

		List<Invocation> invocations() {
			return List.copyOf(invocations);
		}

		int callCount() {
			return callCount.get();
		}

		interface FcmResponder {

			FcmSendResult respond(List<String> tokens, PushMessage message);
		}

		record Invocation(List<String> tokens, PushMessage message, FcmSendResult result, String mdcRequestId) {
		}
	}
}
