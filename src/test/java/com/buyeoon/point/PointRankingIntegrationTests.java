package com.buyeoon.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

/** UC-29 누적 포인트 랭킹 조회의 HTTP 계약을 실제 PostgreSQL에서 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PointRankingIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";
	private static final UUID FIRST_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID MY_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

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

	/** 테스트에서 실제 AWS 호출 없이 Presigned URL을 만들 수 있도록 고정 자격증명을 설정한다. */
	@BeforeAll
	static void configureAwsCredentials() {
		System.setProperty("aws.accessKeyId", "test-access-key");
		System.setProperty("aws.secretAccessKey", "test-secret-key");
	}

	/** 다른 통합 테스트에 AWS 자격증명 시스템 속성을 남기지 않는다. */
	@AfterAll
	static void clearAwsCredentials() {
		System.clearProperty("aws.accessKeyId");
		System.clearProperty("aws.secretAccessKey");
	}

	/** 테스트마다 생성한 랭킹 회원과 포인트 데이터를 참조 역순으로 제거한다. */
	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM point_settlements");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM citizen_cards");
		jdbcTemplate.update("DELETE FROM member_profiles");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 참여 자격을 적용하고 같은 누적 적립에는 공동 순위와 건너뛴 다음 순위를 부여한다. */
	@Test
	@DisplayName("참여 자격을 적용하고 공동 순위를 1, 2, 2 방식으로 반환한다")
	void appliesEligibilityAndSharedRanks() throws Exception {
		AuthenticatedMember me = insertMember(MY_MEMBER_ID, "수정 전", true);
		UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID tied = UUID.fromString("00000000-0000-0000-0000-000000000003");
		UUID zero = UUID.fromString("00000000-0000-0000-0000-000000000004");
		UUID cardless = UUID.fromString("00000000-0000-0000-0000-000000000005");
		UUID withdrawn = UUID.fromString("00000000-0000-0000-0000-000000000006");
		insertMember(first, "일등", false);
		insertMember(tied, "수정 전 동점", false);
		insertMember(zero, "영점", false);
		insertMemberWithoutCard(cardless, false);
		insertMember(withdrawn, "탈퇴", false);
		insertTransaction(first, "EARN", 500);
		insertTransaction(me.memberId(), "EARN", 300);
		insertTransaction(me.memberId(), "ADJUST", 700);
		insertTransaction(tied, "EARN", 300);
		insertTransaction(cardless, "EARN", 900);
		insertTransaction(withdrawn, "EARN", 1000);
		jdbcTemplate.update("""
				UPDATE member_profiles
				SET display_name = '현재 동점', character_id = '10000000-0000-4000-8000-000000000002'
				WHERE member_id = ?
				""", tied);
		withdraw(withdrawn);

		mockMvc.perform(rankingRequest(me)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(3)))
				.andExpect(jsonPath("$.data.items[0].displayName").value("일등"))
				.andExpect(jsonPath("$.data.items[0].rank").value(1))
				.andExpect(jsonPath("$.data.items[1].displayName").value("수정 전"))
				.andExpect(jsonPath("$.data.items[1].rank").value(2))
				.andExpect(jsonPath("$.data.items[1].cumulativeEarned").value(300))
				.andExpect(jsonPath("$.data.items[2].displayName").value("현재 동점"))
				.andExpect(jsonPath("$.data.items[2].rank").value(2))
				.andExpect(
						jsonPath("$.data.items[2].characterImageUrl", containsString("public/characters/geumyong.png")))
				.andExpect(jsonPath("$.data.totalParticipants").value(3));
	}

	/** 공동 순위가 20명 경계에 걸려도 다음 페이지에서 전체 기준 순위를 유지한다. */
	@Test
	@DisplayName("20명 고정 커서 페이지네이션에서 공동 순위의 전체 순위를 유지한다")
	void paginatesTwentyMembersAndKeepsSharedRank() throws Exception {
		AuthenticatedMember viewer = insertMemberWithoutCard(UUID.fromString("00000000-0000-0000-0000-000000000099"),
				true);
		for (int index = 1; index <= 22; index++) {
			UUID memberId = UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", 100 + index));
			insertMember(memberId, "회원" + index, false);
			long points = index <= 19 ? 2000L - index : index <= 21 ? 1000L : 900L;
			insertTransaction(memberId, "EARN", points);
		}

		MvcResult firstPage = mockMvc.perform(rankingRequest(viewer)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items", hasSize(20)))
				.andExpect(jsonPath("$.data.items[19].displayName").value("회원20"))
				.andExpect(jsonPath("$.data.items[19].rank").value(20))
				.andExpect(jsonPath("$.data.page.hasNext").value(true))
				.andExpect(jsonPath("$.data.page.nextCursor").isString())
				.andExpect(jsonPath("$.data.totalParticipants").value(22)).andReturn();
		String cursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.data.page.nextCursor");

		mockMvc.perform(rankingRequest(viewer).param("cursor", cursor)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items", hasSize(2)))
				.andExpect(jsonPath("$.data.items[0].displayName").value("회원21"))
				.andExpect(jsonPath("$.data.items[0].rank").value(20))
				.andExpect(jsonPath("$.data.items[1].displayName").value("회원22"))
				.andExpect(jsonPath("$.data.items[1].rank").value(22))
				.andExpect(jsonPath("$.data.page.hasNext").value(false))
				.andExpect(jsonPath("$.data.page.nextCursor").doesNotExist())
				.andExpect(jsonPath("$.data.totalParticipants").value(22));
	}

	/** 랭킹 참여자가 없고 조회 회원도 미참여이면 문서화된 빈 상태를 반환한다. */
	@Test
	@DisplayName("참여자가 없으면 빈 목록과 미참여 내 랭킹을 반환한다")
	void returnsEmptyRankingAndIneligibleMyRanking() throws Exception {
		AuthenticatedMember viewer = insertMemberWithoutCard(MY_MEMBER_ID, true);

		mockMvc.perform(rankingRequest(viewer)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items", hasSize(0)))
				.andExpect(jsonPath("$.data.myRanking.eligible").value(false))
				.andExpect(jsonPath("$.data.myRanking.rank").value(nullValue()))
				.andExpect(jsonPath("$.data.myRanking.displayName").value(nullValue()))
				.andExpect(jsonPath("$.data.myRanking.characterImageUrl").value(nullValue()))
				.andExpect(jsonPath("$.data.myRanking.cumulativeEarned").value(0))
				.andExpect(jsonPath("$.data.totalParticipants").value(0))
				.andExpect(jsonPath("$.data.page.hasNext").value(false));
	}

	/** 해석할 수 없거나 참여자 경계가 될 수 없는 커서를 공통 400 오류로 거절한다. */
	@Test
	@DisplayName("잘못된 랭킹 커서는 400 INVALID_REQUEST를 반환한다")
	void rejectsInvalidCursor() throws Exception {
		AuthenticatedMember viewer = insertMemberWithoutCard(MY_MEMBER_ID, true);
		String nonPositive = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString(("0_" + MY_MEMBER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8));

		for (String cursor : List.of("not-base64!!", "MTIz", nonPositive)) {
			mockMvc.perform(rankingRequest(viewer).param("cursor", cursor)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		}
	}

	/** 인증 정보가 없는 랭킹 요청은 다른 회원의 랭킹 정보를 공개하지 않는다. */
	@Test
	@DisplayName("인증하지 않으면 401 UNAUTHORIZED를 반환한다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/point-rankings")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 만료 시각이 지난 이월 정산이 있어도 랭킹 조회는 EXPIRE 내역이나 만료 확정을 만들지 않는다. */
	@Test
	@DisplayName("랭킹 조회는 이월 포인트 만료와 다른 DB 변경을 수행하지 않는다")
	void doesNotExpirePointsOrChangeDatabaseState() throws Exception {
		AuthenticatedMember me = insertMember(MY_MEMBER_ID, "금동이", true);
		insertTransaction(MY_MEMBER_ID, "EARN", 100);
		UUID tripId = insertExpiredCarryOver(MY_MEMBER_ID, 100);
		List<Map<String, Object>> beforeTransactions = jdbcTemplate
				.queryForList("SELECT id, member_id, type::text, amount FROM point_transactions ORDER BY id");

		mockMvc.perform(rankingRequest(me)).andExpect(status().isOk());

		assertThat(jdbcTemplate
				.queryForList("SELECT id, member_id, type::text, amount FROM point_transactions ORDER BY id"))
				.isEqualTo(beforeTransactions);
		assertThat(jdbcTemplate.queryForObject("SELECT expired_at FROM point_settlements WHERE trip_id = ?",
				Timestamp.class, tripId)).isNull();
	}

	/** 누적 EARN 내림차순 랭킹과 현재 회원의 순위를 필요한 공개 필드만으로 반환한다. */
	@Test
	@DisplayName("누적 적립 순으로 랭킹 목록과 내 순위를 반환한다")
	void returnsRankingAndMyRankingByCumulativeEarned() throws Exception {
		AuthenticatedMember me = insertMember(MY_MEMBER_ID, "금동이", true);
		insertMember(FIRST_MEMBER_ID, "사비", false);
		insertTransaction(FIRST_MEMBER_ID, "EARN", 200);
		insertTransaction(MY_MEMBER_ID, "EARN", 100);
		insertTransaction(MY_MEMBER_ID, "LEAVE_TO_BUYEO", -80);

		mockMvc.perform(get("/point-rankings").header("Authorization", "Bearer " + me.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.items", hasSize(2))).andExpect(jsonPath("$.data.items[0].rank").value(1))
				.andExpect(jsonPath("$.data.items[0].displayName").value("사비"))
				.andExpect(jsonPath("$.data.items[0].cumulativeEarned").value(200))
				.andExpect(jsonPath("$.data.items[0].isMe").value(false))
				.andExpect(jsonPath("$.data.items[0].characterImageUrl", containsString("public/characters/")))
				.andExpect(jsonPath("$.data.items[0].memberId").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].balance").doesNotExist())
				.andExpect(jsonPath("$.data.items[1].rank").value(2))
				.andExpect(jsonPath("$.data.items[1].displayName").value("금동이"))
				.andExpect(jsonPath("$.data.items[1].cumulativeEarned").value(100))
				.andExpect(jsonPath("$.data.items[1].isMe").value(true))
				.andExpect(jsonPath("$.data.myRanking.eligible").value(true))
				.andExpect(jsonPath("$.data.myRanking.rank").value(2))
				.andExpect(jsonPath("$.data.myRanking.displayName").value("금동이"))
				.andExpect(jsonPath("$.data.myRanking.cumulativeEarned").value(100))
				.andExpect(jsonPath("$.data.totalParticipants").value(2))
				.andExpect(jsonPath("$.data.page.hasNext").value(false));
	}

	/** 활성 회원과 선택적으로 인증 세션·군민증·프로필 fixture를 만든다. */
	private AuthenticatedMember insertMember(UUID memberId, String displayName, boolean authenticated) {
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		UUID characterId = jdbcTemplate.queryForObject("SELECT id FROM card_characters ORDER BY sort_order LIMIT 1",
				UUID.class);
		UUID themeId = jdbcTemplate.queryForObject("SELECT id FROM card_themes ORDER BY sort_order LIMIT 1",
				UUID.class);
		jdbcTemplate.update("INSERT INTO member_profiles (member_id, display_name, character_id) VALUES (?, ?, ?)",
				memberId, displayName, characterId);
		jdbcTemplate.update("INSERT INTO citizen_cards (member_id, theme_id, barcode_value) VALUES (?, ?, ?)", memberId,
				themeId, UUID.randomUUID().toString());
		return authenticate(memberId, authenticated);
	}

	/** 군민증과 프로필 없이 활성 회원과 선택적인 인증 세션만 만든다. */
	private AuthenticatedMember insertMemberWithoutCard(UUID memberId, boolean authenticated) {
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		return authenticate(memberId, authenticated);
	}

	/** 요청자로 사용할 회원에만 활성 인증 세션과 액세스 토큰을 발급한다. */
	private AuthenticatedMember authenticate(UUID memberId, boolean authenticated) {
		if (!authenticated) {
			return new AuthenticatedMember(memberId, null);
		}
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at)
				VALUES (?, ?, ?, ?)
				""", sessionId, memberId, UUID.randomUUID().toString(),
				Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
		return new AuthenticatedMember(memberId, accessTokenService.issue(memberId, sessionId));
	}

	/** 활성 회원을 탈퇴 상태로 전이해 랭킹 제외 fixture를 만든다. */
	private void withdraw(UUID memberId) {
		jdbcTemplate.update("""
				UPDATE members
				SET status = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP,
				    purge_after = CURRENT_TIMESTAMP + INTERVAL '30 days'
				WHERE id = ?
				""", memberId);
	}

	/** 만료 도래 이월 정산을 만들어 랭킹 조회의 무변경 조건을 검증한다. */
	private UUID insertExpiredCarryOver(UUID memberId, long points) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO trips (id, member_id, status, ended_at, settled_at) VALUES (?, ?, 'SETTLED', now(), now())",
				tripId, memberId);
		jdbcTemplate.update("""
				INSERT INTO point_settlements (trip_id, choice, settled_points, expires_at, settled_at)
				VALUES (?, 'CARRY_OVER', ?, CURRENT_TIMESTAMP - INTERVAL '1 minute',
				        CURRENT_TIMESTAMP - INTERVAL '240 hours 1 minute')
				""", tripId, points);
		return tripId;
	}

	/** 인증된 회원의 누적 포인트 랭킹 요청을 만든다. */
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rankingRequest(
			AuthenticatedMember member) {
		return get("/point-rankings").header("Authorization", "Bearer " + member.accessToken());
	}

	/** 회원의 랭킹 계산용 포인트 내역을 저장한다. */
	private void insertTransaction(UUID memberId, String type, long amount) {
		jdbcTemplate.update("""
				INSERT INTO point_transactions (member_id, type, amount, description)
				VALUES (?, ?::point_transaction_type, ?, '랭킹 테스트')
				""", memberId, type, amount);
	}

	/** 테스트 요청에 사용할 회원 ID와 액세스 토큰이다. */
	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}

	/** 통합 테스트용 PostgreSQL 연결과 이미지 버킷 설정을 제공한다. */
	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> APPLICATION_USERNAME);
		registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
		registry.add("storage.images.bucket", () -> "test-images");
		registry.add("point.expiration.initial-delay", () -> "PT24H");
	}
}
