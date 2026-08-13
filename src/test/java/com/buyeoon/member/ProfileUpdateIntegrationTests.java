package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class ProfileUpdateIntegrationTests {

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
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_profile_update ON member_profiles");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_profile_update()");
		jdbcTemplate.update("DELETE FROM citizen_cards");
		jdbcTemplate.update("DELETE FROM member_profiles");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
		jdbcTemplate.update("DELETE FROM card_characters");
		jdbcTemplate.update("DELETE FROM card_themes");
	}

	@Test
	@DisplayName("표시 이름과 캐릭터를 각각 또는 함께 부분 변경한다")
	void updatesProfileFieldsPartiallyAndTogether() throws Exception {
		Fixture fixture = insertIssuedMember();

		performPatch(fixture, "{\"displayName\":\" 새이름 \"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.displayName").value("새이름"))
				.andExpect(jsonPath("$.data.characterId").value(fixture.firstCharacterId().toString()))
				.andExpect(jsonPath("$.data.citizenCardIssued").value(true));
		performPatch(fixture, "{\"characterId\":\"" + fixture.secondCharacterId() + "\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.displayName").value("새이름"))
				.andExpect(jsonPath("$.data.characterId").value(fixture.secondCharacterId().toString()));
		performPatch(fixture, "{\"displayName\":\"최종이름\",\"characterId\":\"" + fixture.firstCharacterId() + "\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("최종이름"));

		assertThat(profile(fixture.memberId())).containsEntry("display_name", "최종이름").containsEntry("character_id",
				fixture.firstCharacterId());
		assertThat(card(fixture.memberId())).containsEntry("theme_id", fixture.themeId()).containsEntry("barcode_value",
				fixture.barcodeValue());
	}

	@Test
	@DisplayName("현재 값과 같은 요청은 프로필 DB 상태를 변경하지 않는다")
	void sameValuesAreNoOp() throws Exception {
		Fixture fixture = insertIssuedMember();
		Map<String, Object> before = profile(fixture.memberId());

		performPatch(fixture, "{\"displayName\":\"기존이름\",\"characterId\":\"" + fixture.firstCharacterId() + "\"}")
				.andExpect(status().isOk());

		assertThat(profile(fixture.memberId())).isEqualTo(before);
	}

	@Test
	@DisplayName("서로 다른 필드의 동시 부분 변경은 모두 보존한다")
	void concurrentPartialUpdatesPreserveBothFields() throws Exception {
		Fixture fixture = insertIssuedMember();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> name = executor
					.submit(() -> concurrentPatch(fixture, "{\"displayName\":\"동시이름\"}", ready, start));
			Future<MvcResult> character = executor.submit(() -> concurrentPatch(fixture,
					"{\"characterId\":\"" + fixture.secondCharacterId() + "\"}", ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(name.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
					character.get(10, TimeUnit.SECONDS).getResponse().getStatus())).containsOnly(200);
		} finally {
			executor.shutdownNow();
		}

		assertThat(profile(fixture.memberId())).containsEntry("display_name", "동시이름").containsEntry("character_id",
				fixture.secondCharacterId());
	}

	@Test
	@DisplayName("잘못된 프로필 요청은 기존 상태를 변경하지 않는다")
	void invalidRequestsDoNotChangeProfile() throws Exception {
		Fixture fixture = insertIssuedMember();
		Map<String, Object> before = profile(fixture.memberId());
		List<String> invalidRequests = List.of("{}", "{\"displayName\":null}", "{\"displayName\":123}",
				"{\"displayName\":\"   \"}", "{\"displayName\":\"123456789\"}", "{\"displayName\":\"이름\\u0001\"}",
				"{\"characterId\":null}", "{\"characterId\":\"not-uuid\"}", "{\"displayName\":\"이름\",\"unknown\":true}",
				"{");

		for (String request : invalidRequests) {
			performPatch(fixture, request).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
			assertThat(profile(fixture.memberId())).isEqualTo(before);
		}

		performPatch(fixture, "{\"characterId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		assertThat(profile(fixture.memberId())).isEqualTo(before);
	}

	@Test
	@DisplayName("군민증 미발급 회원은 프로필을 수정하거나 생성할 수 없다")
	void citizenCardIsRequired() throws Exception {
		Fixture fixture = insertIssuedMember();
		jdbcTemplate.update("DELETE FROM citizen_cards WHERE member_id = ?", fixture.memberId());
		jdbcTemplate.update("DELETE FROM member_profiles WHERE member_id = ?", fixture.memberId());

		performPatch(fixture, "{\"displayName\":\"새프로필\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.data.code").value("INVALID_STATE_TRANSITION"));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_profiles WHERE member_id = ?", Long.class,
				fixture.memberId())).isZero();
	}

	@Test
	@DisplayName("프로필 수정에는 활성 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(patch("/members/me/profile").contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"이름\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("프로필 저장 실패는 기존 프로필과 군민증을 유지한다")
	void storageFailureRollsBackProfile() {
		Fixture fixture = insertIssuedMember();
		Map<String, Object> profileBefore = profile(fixture.memberId());
		Map<String, Object> cardBefore = card(fixture.memberId());
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_profile_update() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced profile failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_profile_update
				BEFORE UPDATE ON member_profiles
				FOR EACH ROW EXECUTE FUNCTION fail_profile_update()
				""");

		assertThatThrownBy(() -> performPatch(fixture, "{\"displayName\":\"실패이름\"}").andReturn())
				.isInstanceOf(Exception.class);
		assertThat(profile(fixture.memberId())).isEqualTo(profileBefore);
		assertThat(card(fixture.memberId())).isEqualTo(cardBefore);
	}

	private MvcResult concurrentPatch(Fixture fixture, String body, CountDownLatch ready, CountDownLatch start)
			throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return performPatch(fixture, body).andReturn();
	}

	private org.springframework.test.web.servlet.ResultActions performPatch(Fixture fixture, String body)
			throws Exception {
		return mockMvc.perform(patch("/members/me/profile").header("Authorization", "Bearer " + fixture.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private Fixture insertIssuedMember() {
		UUID memberId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		UUID firstCharacterId = UUID.randomUUID();
		UUID secondCharacterId = UUID.randomUUID();
		UUID themeId = UUID.randomUUID();
		String barcodeValue = UUID.randomUUID().toString();
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
		jdbcTemplate.update("""
				INSERT INTO card_characters (id, name, image_key, sort_order)
				VALUES (?, '첫 캐릭터', 'public/characters/first.webp', 1),
				       (?, '둘째 캐릭터', 'public/characters/second.webp', 2)
				""", firstCharacterId, secondCharacterId);
		jdbcTemplate.update("""
				INSERT INTO card_themes (id, name, image_key, sort_order)
				VALUES (?, '테마', 'public/themes/theme.webp', 1)
				""", themeId);
		jdbcTemplate.update("""
				INSERT INTO member_profiles (member_id, display_name, character_id, updated_at)
				VALUES (?, '기존이름', ?, CURRENT_TIMESTAMP - INTERVAL '1 hour')
				""", memberId, firstCharacterId);
		jdbcTemplate.update("""
				INSERT INTO citizen_cards (member_id, theme_id, barcode_value)
				VALUES (?, ?, ?)
				""", memberId, themeId, barcodeValue);
		return new Fixture(memberId, firstCharacterId, secondCharacterId, themeId, barcodeValue,
				accessTokenService.issue(memberId, sessionId));
	}

	private Map<String, Object> profile(UUID memberId) {
		return jdbcTemplate.queryForMap("SELECT * FROM member_profiles WHERE member_id = ?", memberId);
	}

	private Map<String, Object> card(UUID memberId) {
		return jdbcTemplate.queryForMap("SELECT * FROM citizen_cards WHERE member_id = ?", memberId);
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

	private record Fixture(UUID memberId, UUID firstCharacterId, UUID secondCharacterId, UUID themeId,
			String barcodeValue, String accessToken) {
	}
}
