package com.buyeoon.location;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

@SpringBootTest(properties = "location.usage-record-purge.initial-delay=PT24H")
@Testcontainers
class LocationUsageRecordIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private LocationUsageRecorder recorder;

	@Autowired
	private LocationUsageRecordPurgeService purgeService;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM location_usage_records");
		jdbcTemplate.update("DELETE FROM members");
	}

	@Test
	@DisplayName("위치 이용 사실에는 좌표 없이 기능과 6개월 보존 시각만 기록한다")
	void recordContainsNoCoordinatesAndExpiresAfterSixMonths() {
		UUID memberId = insertMember();

		recorder.record(memberId, LocationUsagePurpose.TRIP_START);

		Map<String, Object> record = jdbcTemplate.queryForMap("SELECT * FROM location_usage_records");
		Instant usedAt = ((Timestamp) record.get("used_at")).toInstant();
		Instant expiresAt = ((Timestamp) record.get("expires_at")).toInstant();
		assertThat(record.get("member_id")).isEqualTo(memberId);
		assertThat(record.get("purpose")).isEqualTo("TRIP_START");
		assertThat(expiresAt).isEqualTo(usedAt.atZone(java.time.ZoneOffset.UTC).plusMonths(6).toInstant());

		List<String> columns = jdbcTemplate.queryForList("""
				SELECT column_name
				FROM information_schema.columns
				WHERE table_schema = current_schema()
				  AND table_name = 'location_usage_records'
				ORDER BY ordinal_position
				""", String.class);
		assertThat(columns).containsExactly("id", "member_id", "purpose", "used_at", "expires_at");
	}

	@Test
	@DisplayName("만료된 위치 이용 사실은 자동 파기하고 탈퇴 회원 기록은 회원과 함께 삭제한다")
	void expiredAndWithdrawnMemberRecordsAreDeleted() {
		UUID expiredMemberId = insertMember();
		UUID withdrawnMemberId = insertMember();
		recorder.record(expiredMemberId, LocationUsagePurpose.MISSION_DETAIL);
		recorder.record(withdrawnMemberId, LocationUsagePurpose.PLACE_DISTANCE);
		jdbcTemplate.update("""
				UPDATE location_usage_records
				SET used_at = clock_timestamp() - INTERVAL '7 months',
				    expires_at = clock_timestamp() - INTERVAL '1 second'
				WHERE member_id = ?
				""", expiredMemberId);

		assertThat(purgeService.purgeExpired()).isEqualTo(1);
		jdbcTemplate.update("DELETE FROM members WHERE id = ?", withdrawnMemberId);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM location_usage_records", Long.class);
		assertThat(Objects.requireNonNull(count)).isZero();
	}

	private UUID insertMember() {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO members (id, status) VALUES (?, 'ACTIVE')", memberId);
		return memberId;
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
