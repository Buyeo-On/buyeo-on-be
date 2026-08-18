package com.buyeoon.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import org.junit.jupiter.api.AfterEach;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PointTransactionListIntegrationTests {

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
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 내역은 발생 시각 최신순으로 반환되고 각 항목은 발생 시각·내역 ID 순으로 누계한 balanceAfter를 포함한다. */
	@Test
	@DisplayName("발생 시각 최신순으로 모든 필드와 balanceAfter를 반환한다")
	void returnsOwnTransactionsDescendingWithBalanceAfter() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant base = Instant.parse("2026-08-10T00:00:00Z");
		UUID first = insertTransaction(member.memberId(), "EARN", 100, "미션 보상 A", base);
		UUID second = insertTransaction(member.memberId(), "EARN", 50, "미션 보상 B", base.plusSeconds(60));

		mockMvc.perform(get("/members/me/point-transactions").header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.items", hasSize(2)))
				.andExpect(jsonPath("$.data.items[0].transactionId").value(second.toString()))
				.andExpect(jsonPath("$.data.items[0].type").value("EARN"))
				.andExpect(jsonPath("$.data.items[0].amount").value(50))
				.andExpect(jsonPath("$.data.items[0].balanceAfter").value(150))
				.andExpect(jsonPath("$.data.items[0].description").value("미션 보상 B"))
				.andExpect(jsonPath("$.data.items[0].occurredAt").value("2026-08-10T09:01:00+09:00"))
				.andExpect(jsonPath("$.data.items[1].transactionId").value(first.toString()))
				.andExpect(jsonPath("$.data.items[1].balanceAfter").value(100))
				.andExpect(jsonPath("$.data.page.hasNext").value(false))
				.andExpect(jsonPath("$.data.page.nextCursor").doesNotExist());
	}

	/** 동일 발생 시각 내역은 내역 ID 내림차순으로 순서가 흔들리지 않고 balanceAfter도 그 순서로 누계된다. */
	@Test
	@DisplayName("발생 시각이 같으면 내역 ID 순으로 정렬하고 balanceAfter도 그 순서로 누계된다")
	void tieBreaksBySameOccurredAtByTransactionId() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant same = Instant.parse("2026-08-10T00:00:00Z");
		UUID idA = insertTransaction(member.memberId(), "EARN", 10, "동시 적립1", same);
		UUID idB = insertTransaction(member.memberId(), "EARN", 20, "동시 적립2", same);
		UUID idC = insertTransaction(member.memberId(), "EARN", 30, "동시 적립3", same);
		Map<UUID, Long> amountById = Map.of(idA, 10L, idB, 20L, idC, 30L);
		List<UUID> ascendingById = jdbcTemplate.queryForList(
				"SELECT id FROM point_transactions WHERE member_id = ? ORDER BY occurred_at, id", UUID.class,
				member.memberId());
		long cumulative = 0;
		Map<UUID, Long> balanceAfterById = new java.util.LinkedHashMap<>();
		for (UUID id : ascendingById) {
			cumulative += amountById.get(id);
			balanceAfterById.put(id, cumulative);
		}
		List<UUID> descending = ascendingById.reversed();

		mockMvc.perform(get("/members/me/point-transactions").header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(3)))
				.andExpect(jsonPath("$.data.items[0].transactionId").value(descending.get(0).toString()))
				.andExpect(jsonPath("$.data.items[0].balanceAfter").value(balanceAfterById.get(descending.get(0))))
				.andExpect(jsonPath("$.data.items[2].transactionId").value(descending.get(2).toString()))
				.andExpect(jsonPath("$.data.items[2].balanceAfter").value(balanceAfterById.get(descending.get(2))));
	}

	/** cursor로 다음 페이지를 이어서 조회하고 hasNext를 정확히 계산한다. */
	@Test
	@DisplayName("cursor로 다음 페이지를 이어서 조회하고 hasNext를 정확히 계산한다")
	void paginatesWithCursorAndComputesHasNextAccurately() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		Instant base = Instant.parse("2026-08-10T00:00:00Z");
		List<UUID> ids = List.of(insertTransaction(member.memberId(), "EARN", 10, "1", base),
				insertTransaction(member.memberId(), "EARN", 10, "2", base.plusSeconds(1)),
				insertTransaction(member.memberId(), "EARN", 10, "3", base.plusSeconds(2)),
				insertTransaction(member.memberId(), "EARN", 10, "4", base.plusSeconds(3)),
				insertTransaction(member.memberId(), "EARN", 10, "5", base.plusSeconds(4)));
		List<String> expectedDescending = List.of(ids.get(4).toString(), ids.get(3).toString(), ids.get(2).toString(),
				ids.get(1).toString(), ids.get(0).toString());

		MvcResult firstPage = mockMvc
				.perform(get("/members/me/point-transactions").param("size", "2").header("Authorization",
						bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(2)))
				.andExpect(jsonPath("$.data.items[0].transactionId").value(expectedDescending.get(0)))
				.andExpect(jsonPath("$.data.items[1].transactionId").value(expectedDescending.get(1)))
				.andExpect(jsonPath("$.data.page.hasNext").value(true))
				.andExpect(jsonPath("$.data.page.nextCursor").exists()).andReturn();
		String firstCursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.data.page.nextCursor");

		MvcResult secondPage = mockMvc
				.perform(get("/members/me/point-transactions").param("size", "2").param("cursor", firstCursor)
						.header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(2)))
				.andExpect(jsonPath("$.data.items[0].transactionId").value(expectedDescending.get(2)))
				.andExpect(jsonPath("$.data.items[1].transactionId").value(expectedDescending.get(3)))
				.andExpect(jsonPath("$.data.page.hasNext").value(true))
				.andExpect(jsonPath("$.data.page.nextCursor").exists()).andReturn();
		String secondCursor = JsonPath.read(secondPage.getResponse().getContentAsString(), "$.data.page.nextCursor");

		mockMvc.perform(get("/members/me/point-transactions").param("size", "2").param("cursor", secondCursor)
				.header("Authorization", bearer(member))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items", hasSize(1)))
				.andExpect(jsonPath("$.data.items[0].transactionId").value(expectedDescending.get(4)))
				.andExpect(jsonPath("$.data.page.hasNext").value(false))
				.andExpect(jsonPath("$.data.page.nextCursor").doesNotExist());
	}

	/** size는 기본값 20이며 1~100을 벗어나거나 정수가 아니면 400 INVALID_REQUEST다. */
	@Test
	@DisplayName("size는 기본값 20이며 1~100을 벗어나거나 정수가 아니면 400 INVALID_REQUEST다")
	void validatesSizeRange() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		for (int i = 0; i < 21; i++) {
			insertTransaction(member.memberId(), "EARN", 10, "n" + i, Instant.now().plusSeconds(i));
		}

		mockMvc.perform(get("/members/me/point-transactions").header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(20)));

		for (String invalidSize : List.of("0", "101", "abc", "-1")) {
			mockMvc.perform(get("/members/me/point-transactions").param("size", invalidSize).header("Authorization",
					bearer(member))).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		}
	}

	/** 잘못된 형식의 cursor는 400 INVALID_REQUEST다. */
	@Test
	@DisplayName("잘못된 형식의 cursor는 400 INVALID_REQUEST다")
	void validatesCursorFormat() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		for (String invalidCursor : List.of("not-base64!!", "abc", "MTIz")) {
			mockMvc.perform(get("/members/me/point-transactions").param("cursor", invalidCursor).header("Authorization",
					bearer(member))).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
		}
	}

	/** 내역이 없으면 빈 목록과 hasNext false를 반환한다. */
	@Test
	@DisplayName("내역이 없으면 빈 목록과 hasNext false를 반환한다")
	void returnsEmptyListWhenNoTransactions() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();

		mockMvc.perform(get("/members/me/point-transactions").header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(0)))
				.andExpect(jsonPath("$.data.page.hasNext").value(false));
	}

	/** 다른 회원의 내역은 결과에 포함되지 않는다. */
	@Test
	@DisplayName("다른 회원의 내역은 결과에 포함되지 않는다")
	void excludesOtherMembersTransactions() throws Exception {
		AuthenticatedMember me = insertAuthenticatedMember();
		AuthenticatedMember other = insertAuthenticatedMember();
		insertTransaction(other.memberId(), "EARN", 1000, "타인 적립", Instant.now());
		UUID mine = insertTransaction(me.memberId(), "EARN", 10, "내 적립", Instant.now());

		mockMvc.perform(get("/members/me/point-transactions").header("Authorization", bearer(me)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(1)))
				.andExpect(jsonPath("$.data.items[0].transactionId").value(mine.toString()))
				.andExpect(jsonPath("$.data.items[0].balanceAfter").value(10));
	}

	/** 인증되지 않은 요청은 401 UNAUTHORIZED다. */
	@Test
	@DisplayName("인증되지 않은 요청은 401 UNAUTHORIZED다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/members/me/point-transactions")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
	}

	/** 반복 조회해도 DB 상태가 변하지 않는다. */
	@Test
	@DisplayName("반복 조회해도 DB 상태가 변하지 않는다")
	void repeatedCallsDoNotChangeDbState() throws Exception {
		AuthenticatedMember member = insertAuthenticatedMember();
		insertTransaction(member.memberId(), "EARN", 10, "적립", Instant.now());
		List<Map<String, Object>> before = transactions();

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/members/me/point-transactions").header("Authorization", bearer(member)))
					.andExpect(status().isOk());
		}

		assertThat(transactions()).isEqualTo(before);
	}

	private String bearer(AuthenticatedMember member) {
		return "Bearer " + member.accessToken();
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

	private UUID insertTransaction(UUID memberId, String type, long amount, String description, Instant occurredAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO point_transactions (id, member_id, type, amount, description, occurred_at) "
						+ "VALUES (?, ?, ?::point_transaction_type, ?, ?, ?)",
				id, memberId, type, amount, description, Timestamp.from(occurredAt));
		return id;
	}

	private List<Map<String, Object>> transactions() {
		return jdbcTemplate.queryForList("""
				SELECT id, member_id, type::text, amount, description, occurred_at
				FROM point_transactions
				ORDER BY id
				""");
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
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
}
