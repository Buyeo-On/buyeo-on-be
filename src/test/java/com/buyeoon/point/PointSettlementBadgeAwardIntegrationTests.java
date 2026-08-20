package com.buyeoon.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buyeoon.member.auth.AccessTokenService;
import java.net.URI;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * UC-14 양수 포인트를 부여에 남기는 정산으로 {@code POINT_DONATION_COUNT} 배지를 획득하는 흐름의 통합 테스트다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PointSettlementBadgeAwardIntegrationTests {

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

	private AuthenticatedMember member;

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
	void setUpMember() {
		member = insertAuthenticatedMember();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM member_badges");
		jdbcTemplate.update("DELETE FROM badge_conditions");
		jdbcTemplate.update("DELETE FROM badges");
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM point_settlements");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM members");
	}

	/** 첫 기부 정산으로 threshold를 충족하면 response에 배지가 포함되고 획득 이력·알림·정산이 같은 트랜잭션에 확정된다. */
	@Test
	@DisplayName("첫 LEAVE_TO_BUYEO 정산은 기부 배지·획득 이력·알림을 함께 확정한다")
	void firstDonationSettlementAwardsBadgeWithHistoryAndNotification() throws Exception {
		UUID badgeId = insertBadge("기부 천사", "public/badges/donor.png", "포인트 기부 1회");
		insertCondition(badgeId, "POINT_DONATION_COUNT", 1);
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 300, "미션 보상");

		MvcResult result = performSettle(member, tripId, "donation-badge-01", "LEAVE_TO_BUYEO")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].name").value("기부 천사"))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].condition").value("포인트 기부 1회"))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].earnedAt").isNotEmpty())
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].imageUrl").isNotEmpty()).andReturn();

		JsonNode badge = objectMapper.readTree(result.getResponse().getContentAsString()).get("data")
				.get("newlyAwardedBadges").get(0);
		assertThat(URI.create(badge.get("imageUrl").stringValue()).getPath()).isEqualTo("/public/badges/donor.png");

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM member_badges WHERE member_id = ? AND badge_id = ? AND trip_id = ?",
				Integer.class, member.memberId(), badgeId, tripId)).isEqualTo(1);
		Map<String, Object> notification = jdbcTemplate.queryForMap(
				"SELECT title, body, target_type, target_id FROM notifications WHERE member_id = ? AND type = 'BADGE'",
				member.memberId());
		assertThat(notification.get("title")).isEqualTo("새로운 배지를 획득했어요!");
		assertThat(notification.get("body")).isEqualTo("기부 천사 배지를 획득했어요.");
		assertThat(notification.get("target_type")).isEqualTo("BADGE");
		assertThat(notification.get("target_id")).isEqualTo(badgeId);

		assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM trips WHERE id = ?", String.class, tripId))
				.isEqualTo("SETTLED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM point_transactions WHERE member_id = ? AND type = 'LEAVE_TO_BUYEO'",
				Integer.class, member.memberId())).isEqualTo(1);
	}

	/** 기부 배지 획득 알림은 알림 목록 조회에서도 확인할 수 있다. */
	@Test
	@DisplayName("기부 배지 알림은 알림 목록 조회에서도 확인할 수 있다")
	void badgeNotificationIsVisibleThroughNotificationsEndpoint() throws Exception {
		UUID badgeId = insertBadge("기부 천사", null, "포인트 기부 1회");
		insertCondition(badgeId, "POINT_DONATION_COUNT", 1);
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 300, "미션 보상");

		performSettle(member, tripId, "donation-notif-01", "LEAVE_TO_BUYEO").andExpect(status().isOk());

		mockMvc.perform(get("/members/me/notifications").header("Authorization", "Bearer " + member.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].type").value("BADGE"))
				.andExpect(jsonPath("$.data.items[0].title").value("새로운 배지를 획득했어요!"))
				.andExpect(jsonPath("$.data.items[0].body").value("기부 천사 배지를 획득했어요."))
				.andExpect(jsonPath("$.data.items[0].targetType").value("BADGE"))
				.andExpect(jsonPath("$.data.items[0].targetId").value(badgeId.toString()));
	}

	/** 이미지가 없는 기부 배지는 imageUrl이 null이다. */
	@Test
	@DisplayName("이미지가 없는 기부 배지는 imageUrl이 null이다")
	void badgeWithoutImageHasNullImageUrl() throws Exception {
		UUID badgeId = insertBadge("무이미지 기부", null, "포인트 기부 1회");
		insertCondition(badgeId, "POINT_DONATION_COUNT", 1);
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 300, "미션 보상");

		performSettle(member, tripId, "donation-no-image-01", "LEAVE_TO_BUYEO").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].imageUrl").isEmpty());
	}

	/** CARRY_OVER 정산은 기부 배지를 지급하지 않고 donation count도 증가시키지 않는다. */
	@Test
	@DisplayName("CARRY_OVER 정산은 기부 배지를 지급하지 않는다")
	void carryOverDoesNotAwardDonationBadge() throws Exception {
		UUID badgeId = insertBadge("기부 천사", null, "포인트 기부 1회");
		insertCondition(badgeId, "POINT_DONATION_COUNT", 1);
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 200, "미션 보상");

		performSettle(member, tripId, "carry-badge-01", "CARRY_OVER").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges").isArray())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isZero();
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'BADGE'", Integer.class))
				.isZero();
	}

	/** 정산 대상이 없어 NO_POINTS로 정산하면 기부 배지를 지급하지 않는다. */
	@Test
	@DisplayName("정산 대상이 없어 NO_POINTS로 정산하면 기부 배지를 지급하지 않는다")
	void noPointsSettlementDoesNotAwardDonationBadge() throws Exception {
		UUID badgeId = insertBadge("기부 천사", null, "포인트 기부 1회");
		insertCondition(badgeId, "POINT_DONATION_COUNT", 1);
		UUID tripId = insertEndedTrip(member.memberId());

		performSettle(member, tripId, "nopoints-badge-01", null).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.choice").value("NO_POINTS"))
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isZero();
	}

	/** CARRY_OVER는 donation count에 기여하지 않으므로 양수 기부만 threshold를 채워 배지를 지급한다. */
	@Test
	@DisplayName("CARRY_OVER는 donation count에 기여하지 않아 양수 기부만 threshold를 채운다")
	void carryOverDoesNotContributeToDonationCount() throws Exception {
		UUID badgeId = insertBadge("두 번 기부", null, "포인트 기부 2회");
		insertCondition(badgeId, "POINT_DONATION_COUNT", 2);

		UUID carryOverTrip = insertEndedTrip(member.memberId());
		insertTransaction(carryOverTrip, "EARN", 100, "미션 보상");
		performSettle(member, carryOverTrip, "count-carry-01", "CARRY_OVER").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		UUID firstDonation = insertEndedTrip(member.memberId());
		insertTransaction(firstDonation, "EARN", 100, "미션 보상");
		performSettle(member, firstDonation, "count-donate-01", "LEAVE_TO_BUYEO").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(0)));

		UUID secondDonation = insertEndedTrip(member.memberId());
		insertTransaction(secondDonation, "EARN", 100, "미션 보상");
		performSettle(member, secondDonation, "count-donate-02", "LEAVE_TO_BUYEO").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(1)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(badgeId.toString()));
	}

	/** 여러 기부 배지 조건을 함께 충족하면 badge ID 오름차순으로 반환한다. */
	@Test
	@DisplayName("여러 기부 배지를 동시에 충족하면 badge ID 오름차순으로 반환한다")
	void multipleDonationBadgesAreAwardedInBadgeIdAscendingOrder() throws Exception {
		UUID first = insertBadge("배지A", null, "포인트 기부 1회");
		UUID second = insertBadge("배지B", null, "포인트 기부 1회");
		insertCondition(first, "POINT_DONATION_COUNT", 1);
		insertCondition(second, "POINT_DONATION_COUNT", 1);
		// PostgreSQL의 uuid 오름차순은 Java UUID#compareTo와 다를 수 있으므로 DB 정렬 결과를 그대로 사용한다.
		List<UUID> ascending = jdbcTemplate.queryForList("SELECT id FROM badges ORDER BY id ASC", UUID.class);
		UUID expectedFirst = ascending.get(0);
		UUID expectedSecond = ascending.get(1);

		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 300, "미션 보상");

		performSettle(member, tripId, "multi-donation-badge", "LEAVE_TO_BUYEO").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.newlyAwardedBadges", hasSize(2)))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[0].badgeId").value(expectedFirst.toString()))
				.andExpect(jsonPath("$.data.newlyAwardedBadges[1].badgeId").value(expectedSecond.toString()));
	}

	/** 같은 여행의 동시 정산은 하나만 확정하므로 기부 배지 획득 이력과 알림도 한 번만 생성된다. */
	@Test
	@DisplayName("같은 여행의 동시 정산은 기부 배지도 한 번만 지급한다")
	void concurrentSettlementAwardsDonationBadgeExactlyOnce() throws Exception {
		UUID badgeId = insertBadge("기부 천사", null, "포인트 기부 1회");
		insertCondition(badgeId, "POINT_DONATION_COUNT", 1);
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 100, "미션 보상");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor
					.submit(() -> concurrentSettle(member, tripId, "donation-concurrent-a", ready, start));
			Future<MvcResult> second = executor
					.submit(() -> concurrentSettle(member, tripId, "donation-concurrent-b", ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
			int[] statuses = {firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()};
			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isEqualTo(1);
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'BADGE'", Integer.class))
				.isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM point_settlements WHERE trip_id = ?",
				Integer.class, tripId)).isEqualTo(1);
	}

	/** 같은 멱등성 키의 재요청은 최초 지급 결과를 재사용하고 image key로 URL만 다시 생성하며 부작용을 반복하지 않는다. */
	@Test
	@DisplayName("같은 멱등성 키의 재요청은 최초 지급 결과를 재사용하고 image key로 URL만 다시 생성한다")
	void idempotentReplayReusesAwardResultAndRegeneratesImageUrl() throws Exception {
		UUID badgeId = insertBadge("기부 천사", "public/badges/donor.png", "포인트 기부 1회");
		insertCondition(badgeId, "POINT_DONATION_COUNT", 1);
		UUID tripId = insertEndedTrip(member.memberId());
		insertTransaction(tripId, "EARN", 300, "미션 보상");
		String key = "replay-donation-badge";

		MvcResult firstResult = performSettle(member, tripId, key, "LEAVE_TO_BUYEO").andExpect(status().isOk())
				.andReturn();
		MvcResult retriedResult = performSettle(member, tripId, key, "LEAVE_TO_BUYEO").andExpect(status().isOk())
				.andReturn();

		JsonNode firstBadge = objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("data")
				.get("newlyAwardedBadges").get(0);
		JsonNode retriedBadge = objectMapper.readTree(retriedResult.getResponse().getContentAsString()).get("data")
				.get("newlyAwardedBadges").get(0);
		assertThat(retriedBadge.get("badgeId").stringValue()).isEqualTo(firstBadge.get("badgeId").stringValue());
		assertThat(retriedBadge.get("earnedAt").stringValue()).isEqualTo(firstBadge.get("earnedAt").stringValue());
		assertThat(URI.create(retriedBadge.get("imageUrl").stringValue()).getPath())
				.isEqualTo(URI.create(firstBadge.get("imageUrl").stringValue()).getPath());

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_badges WHERE badge_id = ?", Integer.class,
				badgeId)).isEqualTo(1);
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'BADGE'", Integer.class))
				.isEqualTo(1);

		// 만료되는 Presigned URL은 저장하지 않고 image key만 멱등성 record에 보관한다.
		String storedBody = jdbcTemplate.queryForObject(
				"SELECT response_body::text FROM idempotency_requests WHERE member_id = ?", String.class,
				member.memberId());
		assertThat(storedBody).doesNotContain("imageUrl").contains("imageKey");
	}

	private MvcResult concurrentSettle(AuthenticatedMember member, UUID tripId, String key, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
		}
		return performSettle(member, tripId, key, "LEAVE_TO_BUYEO").andReturn();
	}

	private ResultActions performSettle(AuthenticatedMember member, UUID tripId, String idempotencyKey, String choice)
			throws Exception {
		String body = choice == null ? "{}" : "{\"choice\":\"" + choice + "\"}";
		MockHttpServletRequestBuilder request = put("/trips/{tripId}/settlement", tripId)
				.header("Authorization", "Bearer " + member.accessToken()).header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content(body);
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

	private UUID insertEndedTrip(UUID memberId) {
		UUID tripId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO trips (id, member_id, status, ended_at) VALUES (?, ?, 'ENDED', now())", tripId,
				memberId);
		return tripId;
	}

	private void insertTransaction(UUID tripId, String type, long amount, String description) {
		jdbcTemplate.update(
				"INSERT INTO point_transactions (id, member_id, trip_id, type, amount, description, occurred_at) "
						+ "VALUES (?, ?, ?, ?::point_transaction_type, ?, ?, ?)",
				UUID.randomUUID(), member.memberId(), tripId, type, amount, description, Timestamp.from(Instant.now()));
	}

	private UUID insertBadge(String name, String imageKey, String conditionText) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO badges (id, category, name, description, image_key, condition_text) "
						+ "VALUES (?, 'EXPLORATION'::badge_category, ?, '설명', ?, ?)",
				id, name, imageKey, conditionText);
		return id;
	}

	private void insertCondition(UUID badgeId, String metricKey, long threshold) {
		jdbcTemplate.update("INSERT INTO badge_conditions (badge_id, metric_key, threshold) VALUES (?, ?, ?)", badgeId,
				metricKey, threshold);
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
		registry.add("storage.images.bucket", () -> "buyeoon-test-images");
		registry.add("storage.images.region", () -> "ap-northeast-2");
	}

	private record AuthenticatedMember(UUID memberId, String accessToken) {
	}
}
