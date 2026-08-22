package com.buyeoon.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TripStartIntegrationTests {

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
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM citizen_cards");
		jdbcTemplate.update("DELETE FROM member_profiles");
		jdbcTemplate.update("DELETE FROM term_consents");
		jdbcTemplate.update("DELETE FROM terms");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
		jdbcTemplate.update("DELETE FROM card_themes");
	}

	/** 사전조건을 모두 충족한 회원은 여행을 IN_PROGRESS로 시작한다. */
	@Test
	@DisplayName("유효한 요청은 여행을 시작한다")
	void validRequestStartsTrip() throws Exception {
		AuthenticatedMember member = readyMember();

		performStart(member, "start-trip-key-01", request(36.27, 126.91)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.data.startedAt").isString()).andExpect(jsonPath("$.data.endedAt").isEmpty())
				.andExpect(jsonPath("$.data.settledAt").isEmpty());

		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM trips WHERE member_id = ? AND status = 'IN_PROGRESS'",
						Long.class, member.memberId()))
				.isEqualTo(1L);
	}

	/** 필수 약관에 동의하지 않은 회원은 여행을 시작할 수 없다. */
	@Test
	@DisplayName("필수 약관 미동의는 여행 시작을 거부한다")
	void requiredTermsMustBeAgreed() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertRequiredTerms();
		insertCitizenCard(member.memberId());

		performStart(member, "terms-trip-key", request(36.27, 126.91)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("REQUIRED_TERMS_NOT_AGREED"));
		assertNoTrip(member.memberId());
	}

	/** 군민증을 발급받지 않은 회원은 여행을 시작할 수 없다. */
	@Test
	@DisplayName("군민증 미발급은 여행 시작을 거부한다")
	void citizenCardMustBeIssued() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(member.memberId());

		performStart(member, "card-trip-key", request(36.27, 126.91)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("CITIZEN_CARD_NOT_ISSUED"));
		assertNoTrip(member.memberId());
	}

	/** 부여 경계는 포함하고 경계 밖 위치는 여행을 만들지 않는다. */
	@Test
	@DisplayName("부여 경계는 허용하고 외부 위치는 거부한다")
	void boundaryIsIncludedAndOutsideIsRejected() throws Exception {
		AuthenticatedMember outsideMember = readyMember();

		performStart(outsideMember, "outside-trip-key", request(36.5, 127.2)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("OUTSIDE_BUYEO"));
		assertNoTrip(outsideMember.memberId());

		AuthenticatedMember boundaryMember = insertAuthenticatedMember();
		agreeExistingRequiredTerms(boundaryMember.memberId());
		insertCitizenCard(boundaryMember.memberId());
		performStart(boundaryMember, "boundary-trip-key", request(36.2, 126.8)).andExpect(status().isCreated());
	}

	/** 이미 진행 중인 여행이 있으면 새 여행을 만들지 않는다. */
	@Test
	@DisplayName("진행 중인 여행이 있으면 중복 시작을 거부한다")
	void duplicateInProgressTripIsRejected() throws Exception {
		AuthenticatedMember member = readyMember();
		performStart(member, "first-trip-key", request(36.27, 126.91)).andExpect(status().isCreated());

		performStart(member, "second-trip-key", request(36.27, 126.91)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM trips WHERE member_id = ?", Long.class,
				member.memberId())).isEqualTo(1L);
	}

	/** 종료했지만 정산하지 않은 여행이 있으면 새 여행과 성공 멱등성 결과를 만들지 않는다. */
	@Test
	@DisplayName("미정산 여행이 있으면 새 여행 시작을 거부한다")
	void unsettledEndedTripIsRejected() throws Exception {
		AuthenticatedMember member = readyMember();
		insertPastTrip(member.memberId(), "ENDED");

		performStart(member, "unsettled-trip-key", request(36.27, 126.91)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM trips WHERE member_id = ?", Long.class,
				member.memberId())).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests WHERE member_id = ?",
				Long.class, member.memberId())).isZero();
	}

	/** 이전 여행의 정산을 마친 회원은 새 여행을 시작할 수 있다. */
	@Test
	@DisplayName("정산 완료된 여행은 새 여행 시작을 막지 않는다")
	void settledTripAllowsNewStart() throws Exception {
		AuthenticatedMember member = readyMember();
		insertPastTrip(member.memberId(), "SETTLED");

		performStart(member, "settled-trip-key", request(36.27, 126.91)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM trips WHERE member_id = ? AND status = 'IN_PROGRESS'",
						Long.class, member.memberId()))
				.isEqualTo(1L);
	}

	/** 같은 키와 같은 본문의 재요청은 최초 성공 응답을 그대로 반환한다. */
	@Test
	@DisplayName("동일한 멱등성 요청은 최초 응답을 재사용한다")
	void sameIdempotentRequestReturnsFirstResponse() throws Exception {
		AuthenticatedMember member = readyMember();
		String key = "retry-trip-key";

		String first = performStart(member, key, request(36.27, 126.91)).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString();
		String retried = performStart(member, key, request(36.27, 126.91)).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString();

		assertThat(retried).isEqualTo(first);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM trips WHERE member_id = ?", Long.class,
				member.memberId())).isEqualTo(1L);
	}

	/** 같은 키를 다른 요청 본문에 재사용하면 충돌을 반환한다. */
	@Test
	@DisplayName("멱등성 키를 다른 본문에 재사용하면 충돌한다")
	void reusedKeyWithDifferentBodyConflicts() throws Exception {
		AuthenticatedMember member = readyMember();
		performStart(member, "conflict-trip-key", request(36.27, 126.91)).andExpect(status().isCreated());

		performStart(member, "conflict-trip-key", request(36.2, 126.8)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	/** 동시에 도착한 같은 회원의 여행 시작 요청은 하나의 진행 중 여행만 만든다. */
	@Test
	@DisplayName("동시 시작 요청은 진행 중 여행을 하나만 생성한다")
	void concurrentStartRequestsCreateOneInProgressTrip() throws Exception {
		AuthenticatedMember member = readyMember();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor.submit(() -> concurrentStart(member, "concurrent-a", ready, start));
			Future<MvcResult> second = executor.submit(() -> concurrentStart(member, "concurrent-b", ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
			int[] statuses = {firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()};
			assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		} finally {
			executor.shutdownNow();
		}

		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM trips WHERE member_id = ? AND status = 'IN_PROGRESS'",
						Long.class, member.memberId()))
				.isEqualTo(1L);
	}

	/** 여행 저장 중 DB 오류가 발생하면 여행과 멱등성 기록 모두 남지 않는다. */
	@Test
	@DisplayName("여행 저장 실패는 전체 트랜잭션을 롤백한다")
	void storageFailureRollsBackTripAndIdempotency() {
		AuthenticatedMember member = readyMember();
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_trip() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced trip failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_trip_trigger
				BEFORE INSERT ON trips
				FOR EACH ROW EXECUTE FUNCTION fail_trip()
				""");

		try {
			assertThatThrownBy(() -> performStart(member, "rollback-trip-key", request(36.27, 126.91)).andReturn())
					.isInstanceOf(Exception.class);
			assertNoTrip(member.memberId());
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests WHERE member_id = ?",
					Long.class, member.memberId())).isZero();
		} finally {
			jdbcTemplate.execute("DROP TRIGGER fail_trip_trigger ON trips");
			jdbcTemplate.execute("DROP FUNCTION fail_trip()");
		}
	}

	/** 인증되지 않은 요청은 여행 시작 계층에 도달하지 않는다. */
	@Test
	@DisplayName("여행 시작에는 유효한 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(post("/trips").header("Idempotency-Key", "unauth-trip-key")
				.contentType(MediaType.APPLICATION_JSON).content(request(36.27, 126.91)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private MvcResult concurrentStart(AuthenticatedMember member, String key, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return performStart(member, key, request(36.27, 126.91)).andReturn();
	}

	private ResultActions performStart(AuthenticatedMember member, String key, String body) throws Exception {
		var request = post("/trips").header("Authorization", "Bearer " + member.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content(body);
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mockMvc.perform(request);
	}

	private AuthenticatedMember readyMember() {
		AuthenticatedMember member = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(member.memberId());
		insertCitizenCard(member.memberId());
		return member;
	}

	private AuthenticatedMember insertAuthenticatedMember() {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
		return new AuthenticatedMember(memberId, accessTokenService.issue(memberId, sessionId));
	}

	private void agreeToCurrentRequiredTerms(UUID memberId) {
		insertRequiredTerms();
		agreeExistingRequiredTerms(memberId);
	}

	private void agreeExistingRequiredTerms(UUID memberId) {
		jdbcTemplate.update("""
				INSERT INTO term_consents (member_id, term_id, agreed)
				SELECT ?, id, true FROM terms WHERE required = true
				""", memberId);
	}

	private void insertRequiredTerms() {
		Instant effectiveAt = Instant.parse("2026-08-01T00:00:00Z");
		insertTerm("SERVICE", effectiveAt);
		insertTerm("PRIVACY", effectiveAt);
	}

	private void insertTerm(String type, Instant effectiveAt) {
		jdbcTemplate.update("""
				INSERT INTO terms (type, version, required, title, content, effective_at)
				VALUES (?::term_type, '1.0', true, '테스트 약관', '테스트 본문', ?)
				""", type, Timestamp.from(effectiveAt));
	}

	private void insertCitizenCard(UUID memberId) {
		UUID themeId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO card_themes (id, name, image_key, sort_order)
				VALUES (?, '백제', 'public/themes/baekje.webp', ?)
				""", themeId, ThreadLocalRandom.current().nextInt(1, 30_000));
		jdbcTemplate.update("""
				INSERT INTO citizen_cards (member_id, theme_id, barcode_value)
				VALUES (?, ?, ?)
				""", memberId, themeId, UUID.randomUUID().toString());
	}

	/** 종료 또는 정산 완료된 과거 여행 테스트 데이터를 저장한다. */
	private void insertPastTrip(UUID memberId, String status) {
		Instant endedAt = Instant.parse("2026-08-12T09:00:00Z");
		Timestamp settledAt = "SETTLED".equals(status) ? Timestamp.from(endedAt.plus(1, ChronoUnit.HOURS)) : null;
		jdbcTemplate.update("""
				INSERT INTO trips (id, member_id, status, started_at, ended_at, settled_at)
				VALUES (?, ?, ?::trip_status, ?, ?, ?)
				""", UUID.randomUUID(), memberId, status, Timestamp.from(endedAt.minus(1, ChronoUnit.HOURS)),
				Timestamp.from(endedAt), settledAt);
	}

	private String request(double latitude, double longitude) {
		return "{\"location\":{\"latitude\":" + latitude + ",\"longitude\":" + longitude
				+ ",\"accuracyMeters\":5.5,\"capturedAt\":\"2026-08-12T15:30:00+09:00\"}}";
	}

	private void assertNoTrip(UUID memberId) {
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM trips WHERE member_id = ?", Long.class, memberId))
				.isZero();
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
