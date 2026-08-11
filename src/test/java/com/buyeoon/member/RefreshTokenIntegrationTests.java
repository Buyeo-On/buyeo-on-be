package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
class RefreshTokenIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_application";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	private static final String JWT_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	private static final String UNAUTHORIZED_RESPONSE = """
			{"success":false,"data":{"code":"UNAUTHORIZED","message":"인증이 필요합니다."}}
			""";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_migrator").withPassword("migrator-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtDecoder jwtDecoder;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM citizen_cards");
		jdbcTemplate.update("DELETE FROM member_profiles");
		jdbcTemplate.update("DELETE FROM term_consents");
		jdbcTemplate.update("DELETE FROM terms");
		jdbcTemplate.update("DELETE FROM card_characters");
		jdbcTemplate.update("DELETE FROM card_themes");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	void validRefreshTokenRotatesBothTokensAndReturnsLatestMemberState() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		Instant createdAt = Instant.parse("2026-08-11T03:00:00Z");
		RefreshFixture fixture = refreshFixture(sessionId, "initial");
		insertMember(memberId, "ACTIVE", createdAt);
		insertSession(sessionId, memberId, fixture.hash(), Instant.now().plus(30, ChronoUnit.DAYS), null);
		Instant beforeRefresh = Instant.now();

		MvcResult result = refresh(fixture.token()).andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.expiresInSeconds").value(3600))
				.andExpect(jsonPath("$.data.isNewMember").value(false))
				.andExpect(jsonPath("$.data.member.memberId").value(memberId.toString()))
				.andExpect(jsonPath("$.data.member.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.member.requiredTermsAgreed").value(false))
				.andExpect(jsonPath("$.data.member.citizenCardIssued").value(false))
				.andExpect(jsonPath("$.data.member.createdAt").value("2026-08-11T12:00:00+09:00")).andReturn();

		String response = result.getResponse().getContentAsString();
		String accessToken = JsonPath.read(response, "$.data.accessToken");
		String refreshToken = JsonPath.read(response, "$.data.refreshToken");
		assertThat(refreshToken).isNotEqualTo(fixture.token());
		assertThat(refreshToken).startsWith(sessionId + ".");
		String newSecret = refreshToken.substring(refreshToken.indexOf('.') + 1);
		assertThat(Base64.getUrlDecoder().decode(newSecret)).hasSize(32);

		SessionState state = sessionState(sessionId);
		assertThat(state.refreshTokenHash()).isEqualTo(hash(newSecret));
		assertThat(state.refreshTokenHash()).doesNotContain(refreshToken).doesNotContain(newSecret);
		assertThat(state.expiresAt()).isAfter(beforeRefresh.plus(29, ChronoUnit.DAYS));
		assertThat(state.expiresAt()).isBeforeOrEqualTo(Instant.now().plus(30, ChronoUnit.DAYS));

		Jwt jwt = jwtDecoder.decode(accessToken);
		assertThat(jwt.getSubject()).isEqualTo(memberId.toString());
		assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
	}

	@Test
	void rotatedTokenRejectsPreviousTokenWithoutRevokingSession() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		RefreshFixture fixture = refreshFixture(sessionId, "initial");
		insertMember(memberId, "ACTIVE", Instant.now());
		insertSession(sessionId, memberId, fixture.hash(), Instant.now().plus(30, ChronoUnit.DAYS), null);

		String rotatedToken = JsonPath.read(
				refresh(fixture.token()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.data.refreshToken");

		assertUnauthorized(fixture.token());
		assertThat(sessionState(sessionId).revokedAt()).isNull();
		refresh(rotatedToken).andExpect(status().isOk());
	}

	@Test
	void malformedMismatchedExpiredAndRevokedRefreshTokensAreRejected() throws Exception {
		assertUnauthorized("not-a-refresh-token");
		assertUnauthorized(UUID.randomUUID() + ".short");
		assertUnauthorized(UUID.randomUUID() + ".aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.extra");
		mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized()).andExpect(content().json(UNAUTHORIZED_RESPONSE));

		UUID memberId = UUID.randomUUID();
		insertMember(memberId, "ACTIVE", Instant.now());

		UUID mismatchSessionId = UUID.randomUUID();
		RefreshFixture stored = refreshFixture(mismatchSessionId, "stored");
		RefreshFixture mismatch = refreshFixture(mismatchSessionId, "other");
		insertSession(mismatchSessionId, memberId, stored.hash(), Instant.now().plus(30, ChronoUnit.DAYS), null);
		assertUnauthorized(mismatch.token());

		UUID expiredSessionId = UUID.randomUUID();
		RefreshFixture expired = refreshFixture(expiredSessionId, "expired");
		insertSession(expiredSessionId, memberId, expired.hash(), Instant.now().minusSeconds(1), null);
		assertUnauthorized(expired.token());

		UUID revokedSessionId = UUID.randomUUID();
		RefreshFixture revoked = refreshFixture(revokedSessionId, "revoked");
		insertSession(revokedSessionId, memberId, revoked.hash(), Instant.now().plus(30, ChronoUnit.DAYS),
				Instant.now());
		assertUnauthorized(revoked.token());
	}

	@Test
	void withdrawnMemberRefreshTokenIsRejected() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		RefreshFixture fixture = refreshFixture(sessionId, "withdrawn");
		jdbcTemplate.update("""
				INSERT INTO members (id, status, created_at, withdrawn_at, purge_after)
				VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days')
				""", memberId);
		insertSession(sessionId, memberId, fixture.hash(), Instant.now().plus(30, ChronoUnit.DAYS), null);

		assertUnauthorized(fixture.token());
	}

	@Test
	void concurrentRefreshWithSameTokenSucceedsOnlyOnce() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		RefreshFixture fixture = refreshFixture(sessionId, "concurrent");
		insertMember(memberId, "ACTIVE", Instant.now());
		insertSession(sessionId, memberId, fixture.hash(), Instant.now().plus(30, ChronoUnit.DAYS), null);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try {
			var request = (java.util.concurrent.Callable<MvcResult>) () -> {
				ready.countDown();
				start.await();
				return refresh(fixture.token()).andReturn();
			};
			Future<MvcResult> first = executor.submit(request);
			Future<MvcResult> second = executor.submit(request);
			ready.await();
			start.countDown();

			List<Integer> statuses = List
					.of(first.get().getResponse().getStatus(), second.get().getResponse().getStatus()).stream().sorted()
					.toList();
			assertThat(statuses).containsExactly(200, 401);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void failedRotationSaveKeepsPreviousTokenValid() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		RefreshFixture fixture = refreshFixture(sessionId, "rollback");
		insertMember(memberId, "ACTIVE", Instant.now());
		insertSession(sessionId, memberId, fixture.hash(), Instant.now().plus(30, ChronoUnit.DAYS), null);

		executeAsMigrator("REVOKE UPDATE ON auth_sessions FROM buyeoon_application");
		try {
			assertThatThrownBy(() -> refresh(fixture.token()).andReturn())
					.isInstanceOf(jakarta.servlet.ServletException.class);
			assertThat(sessionState(sessionId).refreshTokenHash()).isEqualTo(fixture.hash());
		} finally {
			executeAsMigrator("GRANT UPDATE ON auth_sessions TO buyeoon_application");
		}

		refresh(fixture.token()).andExpect(status().isOk());
	}

	private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
	}

	private void assertUnauthorized(String refreshToken) throws Exception {
		refresh(refreshToken).andExpect(status().isUnauthorized()).andExpect(content().json(UNAUTHORIZED_RESPONSE));
	}

	private void insertMember(UUID memberId, String status, Instant createdAt) {
		jdbcTemplate.update("INSERT INTO members (id, status, created_at) VALUES (?, ?::member_status, ?)", memberId,
				status, Timestamp.from(createdAt));
	}

	private void insertSession(UUID sessionId, UUID memberId, String hash, Instant expiresAt, Instant revokedAt) {
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at, revoked_at)
				VALUES (?, ?, ?, ?, ?)
				""", sessionId, memberId, hash, Timestamp.from(expiresAt),
				revokedAt == null ? null : Timestamp.from(revokedAt));
	}

	private SessionState sessionState(UUID sessionId) {
		return jdbcTemplate.queryForObject("""
				SELECT refresh_token_hash, expires_at, revoked_at
				FROM auth_sessions
				WHERE id = ?
				""", (resultSet, rowNumber) -> new SessionState(resultSet.getString("refresh_token_hash"),
				resultSet.getTimestamp("expires_at").toInstant(),
				resultSet.getTimestamp("revoked_at") == null ? null : resultSet.getTimestamp("revoked_at").toInstant()),
				sessionId);
	}

	private RefreshFixture refreshFixture(UUID sessionId, String salt) throws Exception {
		byte[] secretBytes = MessageDigest.getInstance("SHA-256")
				.digest((sessionId + salt).getBytes(StandardCharsets.UTF_8));
		String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
		return new RefreshFixture(sessionId + "." + secret, hash(secret));
	}

	private String hash(String secret) throws Exception {
		return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
	}

	private void executeAsMigrator(String sql) throws SQLException {
		try (var connection = DriverManager.getConnection(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(),
				POSTGIS.getPassword()); var statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> APPLICATION_USERNAME);
		registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.flyway.url", POSTGIS::getJdbcUrl);
		registry.add("spring.flyway.user", POSTGIS::getUsername);
		registry.add("spring.flyway.password", POSTGIS::getPassword);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
		registry.add("security.jwt.secret-base64", () -> JWT_SECRET);
	}

	private record RefreshFixture(String token, String hash) {
	}

	private record SessionState(String refreshTokenHash, Instant expiresAt, Instant revokedAt) {
	}
}
