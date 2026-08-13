package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
class CitizenCardCreationIntegrationTests {

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
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM citizen_cards");
		jdbcTemplate.update("DELETE FROM member_profiles");
		jdbcTemplate.update("DELETE FROM term_consents");
		jdbcTemplate.update("DELETE FROM terms");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
		jdbcTemplate.update("DELETE FROM card_characters");
		jdbcTemplate.update("DELETE FROM card_themes");
	}

	/** 현재 필수 약관에 동의한 회원은 부여 내부에서 프로필과 군민증을 같은 시각에 한 번 생성한다. */
	@Test
	@DisplayName("부여 내부의 유효한 요청은 군민증을 원자적으로 생성한다")
	void validRequestCreatesCitizenCardAtomically() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(member.memberId());
		Catalog catalog = insertCatalog();

		MvcResult result = performCreate(member, "create-card-key-01",
				request(" 부여인 ", catalog.characterId(), catalog.themeId(), 36.27, 126.91))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.displayName").value("부여인"))
				.andExpect(jsonPath("$.data.character.id").value(catalog.characterId().toString()))
				.andExpect(jsonPath("$.data.theme.id").value(catalog.themeId().toString()))
				.andExpect(jsonPath("$.data.character.imageUrl").isString())
				.andExpect(jsonPath("$.data.theme.imageUrl").isString()).andReturn();

		String response = result.getResponse().getContentAsString();
		assertThat(response).contains("X-Amz-Expires=600");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_profiles", Long.class)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM citizen_cards", Long.class)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isEqualTo(1L);
		assertThat(
				jdbcTemplate.queryForObject("SELECT barcode_value::uuid IS NOT NULL FROM citizen_cards", Boolean.class))
				.isTrue();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT profile.updated_at = card.issued_at
				   AND card.issued_at = request.created_at
				FROM member_profiles profile
				JOIN citizen_cards card ON card.member_id = profile.member_id
				JOIN idempotency_requests request ON request.member_id = profile.member_id
				""", Boolean.class)).isTrue();
	}

	/** 위치 경계는 내부로 포함하고 경계 밖 위치는 상태를 만들지 않은 채 거부한다. */
	@Test
	@DisplayName("부여 경계는 허용하고 외부 위치는 거부한다")
	void boundaryIsIncludedAndOutsideIsRejected() throws Exception {
		AuthenticatedMember outsideMember = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(outsideMember.memberId());
		Catalog catalog = insertCatalog();

		performCreate(outsideMember, "outside-card-key",
				request("외부인", catalog.characterId(), catalog.themeId(), 36.5, 127.2)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("OUTSIDE_BUYEO"));
		assertCreationStateIsEmpty(outsideMember.memberId());

		AuthenticatedMember boundaryMember = insertAuthenticatedMember();
		agreeExistingRequiredTerms(boundaryMember.memberId());
		performCreate(boundaryMember, "boundary-card-key",
				request("경계인", catalog.characterId(), catalog.themeId(), 36.2, 126.8)).andExpect(status().isCreated());
	}

	/** 현재 필수 약관을 모두 동의하지 않은 회원은 군민증을 발급받을 수 없다. */
	@Test
	@DisplayName("현재 필수 약관 미동의는 군민증 생성을 거부한다")
	void requiredTermsMustBeAgreed() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertRequiredTerms();
		Catalog catalog = insertCatalog();

		performCreate(member, "terms-card-key-1",
				request("미동의", catalog.characterId(), catalog.themeId(), 36.27, 126.91))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data.code").value("REQUIRED_TERMS_NOT_AGREED"));
		assertCreationStateIsEmpty(member.memberId());
	}

	/** 이름·식별자·위치·멱등성 헤더의 형식과 승인 카탈로그를 엄격하게 확인한다. */
	@Test
	@DisplayName("잘못된 요청 계약과 승인되지 않은 선택지는 상태를 만들지 않는다")
	void invalidRequestsAreRejectedWithoutStateChange() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(member.memberId());
		Catalog catalog = insertCatalog();
		String valid = request("부여인", catalog.characterId(), catalog.themeId(), 36.27, 126.91);

		performCreate(member, null, valid).andExpect(status().isBadRequest());
		performCreate(member, "short", valid).andExpect(status().isBadRequest());
		performCreate(member, "invalid-name-key", valid.replace("부여인", "가나다라마바사아자")).andExpect(status().isBadRequest());
		performCreate(member, "invalid-field-key", valid.replace("{", "{\"unknown\":true,"))
				.andExpect(status().isBadRequest());
		performCreate(member, "invalid-catalog", request("부여인", UUID.randomUUID(), catalog.themeId(), 36.27, 126.91))
				.andExpect(status().isBadRequest());
		performCreate(member, "invalid-location", valid.replace("36.27", "91.0")).andExpect(status().isBadRequest());
		assertCreationStateIsEmpty(member.memberId());
	}

	/** 같은 키와 정규화된 같은 요청은 최초 201 응답을 그대로 재사용한다. */
	@Test
	@DisplayName("동일한 군민증 생성 재시도는 최초 응답을 반환한다")
	void sameIdempotentRequestReturnsFirstResponse() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(member.memberId());
		Catalog catalog = insertCatalog();
		String key = "retry-card-key-1";

		String first = performCreate(member, key,
				request(" 부여인 ", catalog.characterId(), catalog.themeId(), 36.27, 126.91))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String retried = performCreate(member, key,
				request("부여인", catalog.characterId(), catalog.themeId(), 36.27, 126.91)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		assertThat(retried).isEqualTo(first);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM citizen_cards", Long.class)).isEqualTo(1L);
	}

	/** 보관 중인 키를 다른 본문·작업에 쓰거나 발급 후 다른 키로 다시 만들 수 없다. */
	@Test
	@DisplayName("멱등성 키 재사용과 중복 발급은 충돌한다")
	void reusedKeyAndDuplicateCreationConflict() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(member.memberId());
		Catalog catalog = insertCatalog();
		String original = request("첫이름", catalog.characterId(), catalog.themeId(), 36.27, 126.91);
		performCreate(member, "conflict-card-key", original).andExpect(status().isCreated());

		performCreate(member, "conflict-card-key",
				request("다른이름", catalog.characterId(), catalog.themeId(), 36.27, 126.91))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));
		performCreate(member, "another-card-key", original).andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));
	}

	/** 다른 작업이 보관 중인 키는 거부하지만 만료된 키는 새 군민증 생성에 사용할 수 있다. */
	@Test
	@DisplayName("작업 간 키 재사용은 충돌하고 만료된 키는 재사용할 수 있다")
	void operationConflictAndExpiredKeyAreHandled() throws Exception {
		AuthenticatedMember activeMember = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(activeMember.memberId());
		Catalog catalog = insertCatalog();
		insertIdempotencyRequest(activeMember.memberId(), "shared-operation-key", "UPDATE_TERM_CONSENTS",
				Instant.now().plus(1, ChronoUnit.HOURS));

		performCreate(activeMember, "shared-operation-key",
				request("작업충돌", catalog.characterId(), catalog.themeId(), 36.27, 126.91))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));

		AuthenticatedMember expiredMember = insertAuthenticatedMember();
		agreeExistingRequiredTerms(expiredMember.memberId());
		insertIdempotencyRequest(expiredMember.memberId(), "expired-card-key", "UPDATE_TERM_CONSENTS",
				Instant.now().minus(1, ChronoUnit.HOURS));
		performCreate(expiredMember, "expired-card-key",
				request("만료키", catalog.characterId(), catalog.themeId(), 36.27, 126.91))
				.andExpect(status().isCreated());
		assertThat(jdbcTemplate.queryForObject("""
				SELECT operation = 'CREATE_CITIZEN_CARD' AND expires_at > clock_timestamp()
				FROM idempotency_requests
				WHERE member_id = ? AND idempotency_key = 'expired-card-key'
				""", Boolean.class, expiredMember.memberId())).isTrue();
	}

	/** 동시에 도착한 동일 요청은 프로필·군민증·성공 기록을 한 번만 확정한다. */
	@Test
	@DisplayName("동시 동일 요청은 군민증을 한 번만 생성한다")
	void concurrentSameRequestsAreCommittedOnce() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(member.memberId());
		Catalog catalog = insertCatalog();
		String body = request("동시요청", catalog.characterId(), catalog.themeId(), 36.27, 126.91);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor.submit(() -> concurrentCreate(member, body, ready, start));
			Future<MvcResult> second = executor.submit(() -> concurrentCreate(member, body, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
			assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
			assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
			assertThat(secondResult.getResponse().getContentAsString())
					.isEqualTo(firstResult.getResponse().getContentAsString());
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM citizen_cards", Long.class)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests", Long.class)).isEqualTo(1L);
	}

	/** 군민증 저장 중 DB 오류가 발생하면 앞서 만든 프로필과 멱등성 기록도 남지 않는다. */
	@Test
	@DisplayName("군민증 저장 실패는 전체 트랜잭션을 롤백한다")
	void storageFailureRollsBackProfileCardAndIdempotency() {
		AuthenticatedMember member = insertAuthenticatedMember();
		agreeToCurrentRequiredTerms(member.memberId());
		Catalog catalog = insertCatalog();
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_citizen_card() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced citizen card failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_citizen_card_trigger
				BEFORE INSERT ON citizen_cards
				FOR EACH ROW EXECUTE FUNCTION fail_citizen_card()
				""");

		try {
			assertThatThrownBy(() -> performCreate(member, "rollback-card-key",
					request("롤백", catalog.characterId(), catalog.themeId(), 36.27, 126.91)).andReturn())
					.isInstanceOf(Exception.class);
			assertCreationStateIsEmpty(member.memberId());
		} finally {
			jdbcTemplate.execute("DROP TRIGGER fail_citizen_card_trigger ON citizen_cards");
			jdbcTemplate.execute("DROP FUNCTION fail_citizen_card()");
		}
	}

	/** 인증되지 않은 요청은 군민증 생성 계층에 도달하지 않는다. */
	@Test
	@DisplayName("군민증 생성에는 유효한 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(post("/citizen-cards").header("Idempotency-Key", "unauth-card-key")
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	private MvcResult concurrentCreate(AuthenticatedMember member, String body, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return performCreate(member, "concurrent-card-key", body).andReturn();
	}

	private org.springframework.test.web.servlet.ResultActions performCreate(AuthenticatedMember member, String key,
			String body) throws Exception {
		var request = post("/citizen-cards").header("Authorization", "Bearer " + member.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content(body);
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

	private Catalog insertCatalog() {
		UUID characterId = UUID.randomUUID();
		UUID themeId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO card_characters (id, name, image_key, sort_order)
				VALUES (?, '금동이', 'public/characters/geumdong.webp', 1)
				""", characterId);
		jdbcTemplate.update("""
				INSERT INTO card_themes (id, name, image_key, sort_order)
				VALUES (?, '백제', 'public/themes/baekje.webp', 1)
				""", themeId);
		return new Catalog(characterId, themeId);
	}

	private void insertIdempotencyRequest(UUID memberId, String key, String operation, Instant expiresAt) {
		jdbcTemplate.update("""
				INSERT INTO idempotency_requests
				    (member_id, idempotency_key, operation, request_hash, response_status, response_body, expires_at)
				VALUES (?, ?, ?, 'other-request', 200, '{}'::jsonb, ?)
				""", memberId, key, operation, Timestamp.from(expiresAt));
	}

	private String request(String displayName, UUID characterId, UUID themeId, double latitude, double longitude) {
		return "{\"displayName\":\"" + displayName + "\",\"characterId\":\"" + characterId + "\",\"themeId\":\""
				+ themeId + "\",\"location\":{\"latitude\":" + latitude + ",\"longitude\":" + longitude
				+ ",\"accuracyMeters\":5.5,\"capturedAt\":\"2026-08-12T15:30:00+09:00\"}}";
	}

	private void assertCreationStateIsEmpty(UUID memberId) {
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_profiles WHERE member_id = ?", Long.class,
				memberId)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM citizen_cards WHERE member_id = ?", Long.class,
				memberId)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_requests WHERE member_id = ?",
				Long.class, memberId)).isZero();
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
		registry.add("storage.images.bucket", () -> "buyeoon-test-images");
		registry.add("storage.images.region", () -> "ap-northeast-2");
		registry.add("location.buyeo-boundary", () -> "classpath:boundaries/buyeo-test.geojson");
	}

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}

	private record Catalog(UUID characterId, UUID themeId) {
	}
}
