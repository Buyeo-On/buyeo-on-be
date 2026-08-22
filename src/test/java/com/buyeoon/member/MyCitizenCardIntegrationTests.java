package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MyCitizenCardIntegrationTests {

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

	@BeforeEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM citizen_cards");
		jdbcTemplate.update("DELETE FROM member_profiles");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
		jdbcTemplate.update("DELETE FROM card_characters");
		jdbcTemplate.update("DELETE FROM card_themes");
	}

	/** 조회 시 현재 프로필의 이름·캐릭터와 발급 당시 테마·시각을 조합한다. */
	@Test
	@DisplayName("내 군민증은 현재 프로필과 발급 정보를 함께 반환한다")
	void myCardUsesCurrentProfileAndIssuedCardData() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID firstCharacter = insertCharacter("첫 캐릭터", "public/characters/first.webp", 1);
		UUID currentCharacter = insertCharacter("현재 캐릭터", "public/characters/current.webp", 2);
		UUID theme = insertTheme();
		UUID cardId = UUID.randomUUID();
		Instant issuedAt = Instant.parse("2026-08-12T03:00:00Z");
		jdbcTemplate.update("""
				INSERT INTO member_profiles (member_id, display_name, character_id)
				VALUES (?, '첫이름', ?)
				""", member.memberId(), firstCharacter);
		jdbcTemplate.update("""
				INSERT INTO citizen_cards (id, member_id, theme_id, barcode_value, issued_at)
				VALUES (?, ?, ?, ?, ?)
				""", cardId, member.memberId(), theme, UUID.randomUUID().toString(), Timestamp.from(issuedAt));
		jdbcTemplate.update("""
				UPDATE member_profiles
				SET display_name = '현재이름', character_id = ?, updated_at = clock_timestamp()
				WHERE member_id = ?
				""", currentCharacter, member.memberId());

		MvcResult result = mockMvc
				.perform(get("/citizen-cards/me").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.cardId").value(cardId.toString()))
				.andExpect(jsonPath("$.data.displayName").value("현재이름"))
				.andExpect(jsonPath("$.data.character.id").value(currentCharacter.toString()))
				.andExpect(jsonPath("$.data.character.name").value("현재 캐릭터"))
				.andExpect(jsonPath("$.data.theme.id").value(theme.toString()))
				.andExpect(jsonPath("$.data.theme.name").value("백제 테마")).andReturn();

		JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
		assertThat(data.get("character").get("imageUrl").stringValue()).contains("X-Amz-Expires=600");
		assertThat(data.get("theme").get("imageUrl").stringValue()).contains("X-Amz-Expires=600");
		assertThat(OffsetDateTime.parse(data.get("issuedAt").stringValue()).toInstant()).isEqualTo(issuedAt);
	}

	/** 군민증을 아직 발급받지 않은 인증 회원은 404를 받는다. */
	@Test
	@DisplayName("미발급 회원의 내 군민증 조회는 찾을 수 없음이다")
	void missingCitizenCardReturnsNotFound() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		mockMvc.perform(get("/citizen-cards/me").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
	}

	/** 인증되지 않은 요청은 내 군민증 정보를 볼 수 없다. */
	@Test
	@DisplayName("내 군민증 조회에는 유효한 인증 세션이 필요하다")
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(get("/citizen-cards/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 바코드 조회는 군민증과 UUID 바코드, 양수·음수 포인트 원장 합계를 상태 변경 없이 반환한다. */
	@Test
	@DisplayName("바코드 조회는 시연용 바코드와 현재 포인트 잔액을 반환한다")
	void barcodeReturnsCitizenCardAndCurrentPointBalanceWithoutMutation() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID character = insertCharacter("금동이", "public/characters/geumdong.webp", 1);
		UUID theme = insertTheme();
		UUID cardId = issueCitizenCard(member.memberId(), character, theme);
		insertPoint(member.memberId(), "EARN", 1000);
		insertPoint(member.memberId(), "LEAVE_TO_BUYEO", -300);
		Long beforeCount = jdbcTemplate.queryForObject("SELECT count(*) FROM point_transactions", Long.class);

		mockMvc.perform(get("/citizen-cards/me/barcode").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.citizenCard.cardId").value(cardId.toString()))
				.andExpect(jsonPath("$.data.barcodeValue").isString())
				.andExpect(jsonPath("$.data.pointBalance").value(700))
				.andExpect(jsonPath("$.data.simulationOnly").value(true))
				.andExpect(jsonPath("$.data.notice").value("실제 상점에서의 사용은 제한됩니다."));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM point_transactions", Long.class))
				.isEqualTo(beforeCount);
		assertThat(jdbcTemplate.queryForObject("SELECT sum(amount) FROM point_transactions", Long.class))
				.isEqualTo(700L);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM citizen_cards", Long.class)).isEqualTo(1L);
	}

	/** 포인트 내역이 없는 발급 회원은 0 잔액을 받는다. */
	@Test
	@DisplayName("포인트 내역이 없으면 바코드 잔액은 0이다")
	void barcodeBalanceIsZeroWithoutPointTransactions() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		UUID character = insertCharacter("금동이", "public/characters/geumdong.webp", 1);
		UUID theme = insertTheme();
		issueCitizenCard(member.memberId(), character, theme);

		mockMvc.perform(get("/citizen-cards/me/barcode").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.pointBalance").value(0));
	}

	/** 미발급·미인증 회원은 바코드와 잔액을 조회할 수 없다. */
	@Test
	@DisplayName("바코드 조회는 발급된 군민증과 인증을 요구한다")
	void barcodeRequiresIssuedCardAndAuthentication() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		mockMvc.perform(get("/citizen-cards/me/barcode").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
		mockMvc.perform(get("/citizen-cards/me/barcode")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
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

	private UUID insertCharacter(String name, String imageKey, int sortOrder) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO card_characters (id, name, image_key, sort_order)
				VALUES (?, ?, ?, ?)
				""", id, name, imageKey, sortOrder);
		return id;
	}

	private UUID insertTheme() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO card_themes (id, name, image_key, sort_order)
				VALUES (?, '백제 테마', 'public/themes/baekje.webp', 1)
				""", id);
		return id;
	}

	private UUID issueCitizenCard(UUID memberId, UUID characterId, UUID themeId) {
		UUID cardId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO member_profiles (member_id, display_name, character_id)
				VALUES (?, '부여인', ?)
				""", memberId, characterId);
		jdbcTemplate.update("""
				INSERT INTO citizen_cards (id, member_id, theme_id, barcode_value)
				VALUES (?, ?, ?, ?)
				""", cardId, memberId, themeId, UUID.randomUUID().toString());
		return cardId;
	}

	private void insertPoint(UUID memberId, String type, long amount) {
		jdbcTemplate.update("""
				INSERT INTO point_transactions (member_id, type, amount, description)
				VALUES (?, ?::point_transaction_type, ?, '테스트 포인트')
				""", memberId, type, amount);
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
	}

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
