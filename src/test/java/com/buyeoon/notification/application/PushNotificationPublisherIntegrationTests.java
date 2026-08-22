package com.buyeoon.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.push.FcmClient;
import com.buyeoon.notification.push.PushMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
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
				VALUES (?, false, false, 0)
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

	/** 실제 FCM 대신 발송 요청을 기록하는 테스트 대역이다. */
	static class FakeFcmClient implements FcmClient {

		private final List<Invocation> invocations = new CopyOnWriteArrayList<>();
		private volatile CountDownLatch latch = new CountDownLatch(0);

		@Override
		public void sendMulticast(List<String> registrationTokens, PushMessage message) {
			invocations.add(new Invocation(List.copyOf(registrationTokens), message));
			latch.countDown();
		}

		void reset() {
			invocations.clear();
			latch = new CountDownLatch(0);
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

		record Invocation(List<String> tokens, PushMessage message) {
		}
	}
}
