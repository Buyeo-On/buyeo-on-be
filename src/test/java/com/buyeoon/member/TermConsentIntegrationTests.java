package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TermConsentIntegrationTests {

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

	@Autowired
	private ObjectMapper objectMapper;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM term_consents");
		jdbcTemplate.update("DELETE FROM terms");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 현재 약관 전체의 결정을 같은 시각으로 저장하고 과거 버전 이력은 보존한다. */
	@Test
	@DisplayName("현재 필수 약관 동의와 마케팅 거부를 원자적으로 저장한다")
	void currentRequiredTermsAndMarketingRejectionAreStoredAtomically() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID oldServiceId = insertTerm("SERVICE", "0.9", true, Instant.parse("2026-01-01T00:00:00Z"));
		jdbcTemplate.update("INSERT INTO term_consents (member_id, term_id, agreed) VALUES (?, ?, true)",
				member.memberId(), oldServiceId);
		CurrentTerms terms = insertCurrentTerms();

		// 실제 JWT 인증 HTTP 요청으로 필수 동의와 선택 거부를 함께 저장한다.
		MvcResult response = performConsent(member, "consent-key-0001", request(terms, true, true, true, false))
				.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.requiredTermsAgreed").value(true))
				.andExpect(jsonPath("$.data.agreedAt").isString()).andReturn();

		String agreedAtText = objectMapper.readTree(response.getResponse().getContentAsString()).get("data")
				.get("agreedAt").stringValue();
		Instant responseAgreedAt = OffsetDateTime.parse(agreedAtText).toInstant();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(DISTINCT agreed_at)
				FROM term_consents
				WHERE member_id = ? AND term_id <> ?
				""", Long.class, member.memberId(), oldServiceId)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT agreed_at
				FROM term_consents
				WHERE member_id = ? AND term_id = ?
				""", Timestamp.class, member.memberId(), terms.serviceId()).toInstant()).isEqualTo(responseAgreedAt);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT agreed
				FROM term_consents
				WHERE member_id = ? AND term_id = ?
				""", Boolean.class, member.memberId(), terms.marketingId())).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term_consents WHERE member_id = ?", Long.class,
				member.memberId())).isEqualTo(5L);

		mockMvc.perform(get("/members/me").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.requiredTermsAgreed").value(true));
	}

	/** 약관 누락·중복과 필수 약관 거부는 400이며 어떤 상태도 만들지 않는다. */
	@Test
	@DisplayName("불완전하거나 필수 약관을 거부한 요청은 전체를 거부한다")
	void missingDuplicateAndRejectedRequiredTermsAreRejected() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		CurrentTerms terms = insertCurrentTerms();
		String missing = requestWithoutMarketing(terms);
		String duplicate = requestWithDuplicateService(terms);
		String rejectedRequired = request(terms, false, true, true, false);

		// 실패 요청은 성공 멱등성 기록이나 일부 동의를 남기지 않는다.
		for (String body : new String[]{missing, duplicate, rejectedRequired}) {
			performConsent(member, "invalid-key-0001", body).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term_consents", Long.class)).isZero();
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isZero();
		}
	}

	/** 사용자가 본 뒤 새 버전이 시행된 약관 요청은 409이며 기존 상태를 변경하지 않는다. */
	@Test
	@DisplayName("현재 버전과 다른 약관은 다시 조회하도록 충돌을 반환한다")
	void outdatedTermVersionIsRejected() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		CurrentTerms viewedTerms = insertCurrentTerms();
		insertTerm("SERVICE", "2.0", true, Instant.now().minus(1, ChronoUnit.HOURS));

		// 조회 이후 현재 버전이 바뀐 상황을 HTTP 409로 검증한다.
		performConsent(member, "outdated-key-01", request(viewedTerms, true, true, true, false))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("TERM_VERSION_OUTDATED"));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term_consents", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isZero();
	}

	/** 인증되지 않은 약관 동의 요청은 저장 계층에 도달하지 않고 401로 거부한다. */
	@Test
	@DisplayName("약관 동의 저장은 유효한 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		CurrentTerms terms = insertCurrentTerms();

		// 인증 헤더가 없는 공개 HTTP 요청은 보안 필터에서 거부한다.
		mockMvc.perform(put("/members/me/term-consents").header("Idempotency-Key", "unauth-key-0001")
				.contentType(MediaType.APPLICATION_JSON).content(request(terms, true, true, true, false)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term_consents", Long.class)).isZero();
	}

	/** 멱등성 키 누락·길이 오류와 정의되지 않은 요청 필드는 400으로 거부한다. */
	@Test
	@DisplayName("약관 동의 요청은 헤더와 JSON 계약을 엄격하게 검증한다")
	void idempotencyHeaderAndJsonContractAreValidated() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		CurrentTerms terms = insertCurrentTerms();
		String body = request(terms, true, true, true, false);

		// OpenAPI의 필수 헤더 길이와 additionalProperties false를 검증한다.
		mockMvc.perform(put("/members/me/term-consents").header("Authorization", "Bearer " + member.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		performConsent(member, "short", body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		performConsent(member, "strict-key-0001", body.replace("{\"consents\":", "{\"unknown\":true,\"consents\":"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term_consents", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isZero();
	}

	/** 새 필수 약관 버전이 시행되면 과거 동의 이력을 유지하면서 현재 동의 상태는 false가 된다. */
	@Test
	@DisplayName("새 필수 약관 시행 후에는 다시 동의하기 전까지 미동의 상태다")
	void newRequiredTermVersionRequiresConsentAgain() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		CurrentTerms terms = insertCurrentTerms();
		performConsent(member, "new-version-key", request(terms, true, true, true, false)).andExpect(status().isOk());
		insertTerm("SERVICE", "2.0", true, Instant.now().minus(1, ChronoUnit.HOURS));

		// 과거 버전 동의 행은 남지만 현재 필수 버전에 대한 동의가 없으므로 false다.
		mockMvc.perform(get("/members/me").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.requiredTermsAgreed").value(false));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term_consents WHERE member_id = ?", Long.class,
				member.memberId())).isEqualTo(4L);
	}

	/** 같은 키와 같은 결정은 배열 순서가 달라도 최초 성공 응답을 그대로 반환한다. */
	@Test
	@DisplayName("동일한 약관 동의 재시도는 최초 성공 응답을 반환한다")
	void sameIdempotencyKeyAndNormalizedRequestReturnsFirstResponse() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		CurrentTerms terms = insertCurrentTerms();
		String key = "retry-key-00001";

		// termId 순서가 달라도 같은 요청으로 정규화되는지 공개 HTTP 응답으로 검증한다.
		String firstResponse = performConsent(member, key, request(terms, true, true, true, false))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String retriedResponse = performConsent(member, key, reversedRequest(terms, true, true, true, false))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(retriedResponse).isEqualTo(firstResponse);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term_consents", Long.class)).isEqualTo(4L);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isEqualTo(1L);
	}

	/** 보관 중인 같은 키를 다른 약관 결정에 사용하면 409이며 최초 결과를 유지한다. */
	@Test
	@DisplayName("같은 멱등성 키를 다른 요청에 재사용할 수 없다")
	void idempotencyKeyCannotBeReusedForDifferentRequest() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		CurrentTerms terms = insertCurrentTerms();
		String key = "reused-key-0001";
		performConsent(member, key, request(terms, true, true, true, false)).andExpect(status().isOk());

		// 마케팅 결정을 바꾼 두 번째 요청은 기존 성공을 덮어쓰지 않는다.
		performConsent(member, key, request(terms, true, true, true, true)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));
		assertThat(jdbcTemplate.queryForObject("SELECT agreed FROM term_consents WHERE term_id = ?", Boolean.class,
				terms.marketingId())).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isEqualTo(1L);
	}

	/** 동시에 도착한 같은 요청은 하나만 확정하고 두 호출 모두 같은 성공을 관찰한다. */
	@Test
	@DisplayName("동시 동일 요청은 약관 동의와 멱등성 응답을 한 번만 확정한다")
	void concurrentSameRequestsAreCommittedOnce() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		CurrentTerms terms = insertCurrentTerms();
		String body = request(terms, true, true, true, false);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor.submit(() -> concurrentConsent(member, body, ready, start));
			Future<MvcResult> second = executor.submit(() -> concurrentConsent(member, body, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
			assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
			assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
			assertThat(secondResult.getResponse().getContentAsString())
					.isEqualTo(firstResult.getResponse().getContentAsString());
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term_consents", Long.class)).isEqualTo(4L);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isEqualTo(1L);
	}

	/** 24시간이 지난 키는 기존 레코드를 교체하고 새 요청에 사용할 수 있다. */
	@Test
	@DisplayName("만료된 멱등성 키는 새 약관 결정에 재사용할 수 있다")
	void expiredIdempotencyKeyCanBeReused() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		CurrentTerms terms = insertCurrentTerms();
		String key = "expired-key-001";
		performConsent(member, key, request(terms, true, true, true, false)).andExpect(status().isOk());
		jdbcTemplate.update("""
				UPDATE idempotency_requests
				SET created_at = clock_timestamp() - INTERVAL '25 hours',
				    expires_at = clock_timestamp() - INTERVAL '1 hour'
				WHERE member_id = ? AND idempotency_key = ?
				""", member.memberId(), key);

		// 만료 후에는 같은 키로 바뀐 마케팅 결정을 새로 확정할 수 있다.
		performConsent(member, key, request(terms, true, true, true, true)).andExpect(status().isOk());
		assertThat(jdbcTemplate.queryForObject("SELECT agreed FROM term_consents WHERE term_id = ?", Boolean.class,
				terms.marketingId())).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT expires_at > clock_timestamp() FROM idempotency_requests",
				Boolean.class)).isTrue();
	}

	/** 동의 저장 도중 DB 오류가 발생하면 앞선 동의와 성공 멱등성 기록도 남지 않는다. */
	@Test
	@DisplayName("약관 동의 저장 실패는 전체 트랜잭션을 롤백한다")
	void storageFailureRollsBackAllConsentsAndIdempotencyRecord() {
		AuthenticatedMember member = insertAuthenticatedMember();
		CurrentTerms terms = insertCurrentTerms();
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_rejected_consent() RETURNS trigger AS $$
				BEGIN
				    IF NEW.agreed = false THEN
				        RAISE EXCEPTION 'forced consent failure';
				    END IF;
				    RETURN NEW;
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_rejected_consent_trigger
				BEFORE INSERT OR UPDATE ON term_consents
				FOR EACH ROW EXECUTE FUNCTION fail_rejected_consent()
				""");

		try {
			// 세 번째 마케팅 동의 저장 실패가 앞선 두 필수 동의까지 되돌리는지 확인한다.
			assertThatThrownBy(() -> performConsent(member, "rollback-key-001", request(terms, true, true, true, false))
					.andReturn()).isInstanceOf(Exception.class);
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term_consents", Long.class)).isZero();
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isZero();
		} finally {
			jdbcTemplate.execute("DROP TRIGGER fail_rejected_consent_trigger ON term_consents");
			jdbcTemplate.execute("DROP FUNCTION fail_rejected_consent()");
		}
	}

	private MvcResult concurrentConsent(AuthenticatedMember member, String body, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return performConsent(member, "concurrent-key-1", body).andReturn();
	}

	private org.springframework.test.web.servlet.ResultActions performConsent(AuthenticatedMember member,
			String idempotencyKey, String body) throws Exception {
		return mockMvc.perform(put("/members/me/term-consents")
				.header("Authorization", "Bearer " + member.accessToken()).header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content(body));
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

	private CurrentTerms insertCurrentTerms() {
		Instant effectiveAt = Instant.parse("2026-08-01T00:00:00Z");
		return new CurrentTerms(insertTerm("SERVICE", "1.0", true, effectiveAt),
				insertTerm("PRIVACY", "1.0", true, effectiveAt), insertTerm("LOCATION", "1.0", true, effectiveAt),
				insertTerm("MARKETING", "1.0", false, effectiveAt));
	}

	private UUID insertTerm(String type, String version, boolean required, Instant effectiveAt) {
		UUID termId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO terms (id, type, version, required, title, content, effective_at)
				VALUES (?, ?::term_type, ?, ?, '테스트 약관', '테스트 본문', ?)
				""", termId, type, version, required, Timestamp.from(effectiveAt));
		return termId;
	}

	private String request(CurrentTerms terms, boolean service, boolean privacy, boolean location, boolean marketing) {
		return ("{\"consents\":[{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":%s},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":%s},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":%s},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":%s}]}").formatted(terms.serviceId(), service,
						terms.privacyId(), privacy, terms.locationId(), location, terms.marketingId(), marketing);
	}

	private String reversedRequest(CurrentTerms terms, boolean service, boolean privacy, boolean location,
			boolean marketing) {
		return ("{\"consents\":[{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":%s},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":%s},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":%s},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":%s}]}").formatted(terms.marketingId(), marketing,
						terms.locationId(), location, terms.privacyId(), privacy, terms.serviceId(), service);
	}

	private String requestWithoutMarketing(CurrentTerms terms) {
		return ("{\"consents\":[{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":true},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":true},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":true}]}")
				.formatted(terms.serviceId(), terms.privacyId(), terms.locationId());
	}

	private String requestWithDuplicateService(CurrentTerms terms) {
		return ("{\"consents\":[{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":true},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":true},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":true},"
				+ "{\"termId\":\"%s\",\"version\":\"1.0\",\"agreed\":true}]}")
				.formatted(terms.serviceId(), terms.privacyId(), terms.marketingId(), terms.serviceId());
	}

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
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

	private record CurrentTerms(UUID serviceId, UUID privacyId, UUID locationId, UUID marketingId) {
	}
}
