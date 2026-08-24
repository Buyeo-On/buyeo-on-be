package com.buyeoon.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 알림 도메인이 회원 도메인의 Repository 없이 발송 대상 토큰을 조회하는 공개 seam을 검증한다. */
@SpringBootTest
@Testcontainers
class PushTargetQueryServiceIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private PushTargetQueryService pushTargetQueryService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM push_tokens");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("활성 회원의 미폐기·미만료 세션이 여러 개이면 각 세션에 연결된 모든 등록 토큰을 반환한다")
	void returnsTokensForEveryActiveSession() {
		UUID memberId = insertActiveMember(true, true);
		UUID firstSession = insertSession(memberId, null, 30);
		UUID secondSession = insertSession(memberId, null, 30);
		insertPushToken(firstSession, "device-1");
		insertPushToken(secondSession, "device-2");

		List<String> tokens = pushTargetQueryService.findRegistrationTokens(memberId);

		assertThat(tokens).containsExactlyInAnyOrder("device-1", "device-2");
	}

	@Test
	@DisplayName("탈퇴 회원과 폐기·만료 세션의 등록 토큰은 반환하지 않는다")
	void excludesWithdrawnMemberAndRevokedOrExpiredSessions() {
		UUID activeMemberId = insertActiveMember(true, true);
		UUID revokedSession = insertSession(activeMemberId, Instant.now(), 30);
		UUID expiredSession = insertSession(activeMemberId, null, -1);
		insertPushToken(revokedSession, "revoked-token");
		insertPushToken(expiredSession, "expired-token");

		UUID withdrawnMemberId = insertWithdrawnMember();
		UUID withdrawnSession = insertSession(withdrawnMemberId, null, 30);
		insertPushToken(withdrawnSession, "withdrawn-token");

		assertThat(pushTargetQueryService.findRegistrationTokens(activeMemberId)).isEmpty();
		assertThat(pushTargetQueryService.findRegistrationTokens(withdrawnMemberId)).isEmpty();
	}

	@Test
	@DisplayName("마케팅 동의 값과 무관하게 동일한 발송 대상 토큰을 반환한다")
	void returnsSameTargetsRegardlessOfMarketingConsent() {
		UUID memberId = insertActiveMember(true, false);
		UUID session = insertSession(memberId, null, 30);
		insertPushToken(session, "device-token");

		List<String> tokens = pushTargetQueryService.findRegistrationTokens(memberId);

		assertThat(tokens).containsExactly("device-token");
	}

	@Test
	@DisplayName("알림 동의가 꺼진 회원의 등록 토큰은 반환하지 않는다")
	void excludesMembersWithNotificationsDisabled() {
		UUID optedOutMemberId = insertActiveMember(false, true);
		UUID session = insertSession(optedOutMemberId, null, 30);
		insertPushToken(session, "device-token");

		List<String> tokens = pushTargetQueryService.findRegistrationTokens(optedOutMemberId);

		assertThat(tokens).isEmpty();
	}

	private UUID insertActiveMember(boolean nearbyQuizNotificationEnabled, boolean marketingConsentAgreed) {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO member_settings
				    (member_id, nearby_quiz_notification_enabled, dark_mode_enabled, version)
				VALUES (?, ?, false, 0)
				""", memberId, nearbyQuizNotificationEnabled);
		return memberId;
	}

	private UUID insertWithdrawnMember() {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO members (id, status, withdrawn_at, purge_after)
				VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days')
				""", memberId);
		return memberId;
	}

	private UUID insertSession(UUID memberId, Instant revokedAt, long expiresInDays) {
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at, revoked_at)
				VALUES (?, ?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(expiresInDays, ChronoUnit.DAYS)),
				revokedAt == null ? null : Timestamp.from(revokedAt));
		return sessionId;
	}

	private void insertPushToken(UUID sessionId, String registrationToken) {
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
}
