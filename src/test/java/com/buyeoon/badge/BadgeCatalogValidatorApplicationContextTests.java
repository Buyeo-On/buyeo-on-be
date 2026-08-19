package com.buyeoon.badge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buyeoon.BuyeoOnApplication;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * UC-14 badge catalog startup 검증의 ApplicationContext 통합 테스트다. 실제
 * PostgreSQL/Flyway schema에 각 시나리오의 fixture를 적재한 뒤 애플리케이션을 실제로 기동해 검증이 startup
 * 시점에 동작함을 확인한다. Flyway 마이그레이션은 fixture를 적재할 테이블이 필요하므로 컨테이너 시작 직후 애플리케이션과 별개로
 * 한 번 적용한다.
 */
@Testcontainers
class BadgeCatalogValidatorApplicationContextTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	private ConfigurableApplicationContext context;

	@BeforeAll
	static void migrateSchema() {
		Flyway.configure().dataSource(POSTGIS.getJdbcUrl(), APPLICATION_USERNAME, APPLICATION_PASSWORD).load()
				.migrate();
	}

	@AfterEach
	void cleanUp() {
		if (context != null) {
			context.close();
		}
		JdbcTemplate jdbcTemplate = jdbcTemplate();
		jdbcTemplate.update("DELETE FROM member_badges");
		jdbcTemplate.update("DELETE FROM badge_conditions");
		jdbcTemplate.update("DELETE FROM badges");
	}

	@Test
	@DisplayName("Empty catalog는 애플리케이션을 정상 기동한다")
	void emptyCatalogStartsSuccessfully() {
		context = startApplication();

		assertThat(context.isActive()).isTrue();
	}

	@Test
	@DisplayName("조건과 지원 Provider를 갖춘 catalog는 애플리케이션을 정상 기동한다")
	void validCatalogStartsSuccessfully() {
		insertBadgeWithCondition("탐험가", "MISSION_COMPLETED_COUNT", null);

		context = startApplication();

		assertThat(context.isActive()).isTrue();
	}

	@Test
	@DisplayName("Condition이 없는 지급 가능 배지가 있으면 명확한 원인과 함께 startup이 실패한다")
	void badgeWithoutConditionsFailsStartup() {
		insertBadgeWithoutCondition("무조건 배지", null);

		assertThatThrownBy(this::startApplication).hasStackTraceContaining("무조건 배지").hasStackTraceContaining("조건");
	}

	@Test
	@DisplayName("등록된 Provider가 없는 metric을 가진 지급 가능 배지가 있으면 명확한 원인과 함께 startup이 실패한다")
	void badgeWithUnsupportedMetricFailsStartup() {
		insertBadgeWithCondition("미지원 메트릭 배지", "UNSUPPORTED_METRIC", null);

		assertThatThrownBy(this::startApplication).hasStackTraceContaining("미지원 메트릭 배지")
				.hasStackTraceContaining("UNSUPPORTED_METRIC");
	}

	@Test
	@DisplayName("Retired badge는 조건이 없거나 미지원 metric이어도 startup을 막지 않는다")
	void retiredBadgeDoesNotBlockStartup() {
		Instant retiredAt = Instant.now();
		insertBadgeWithoutCondition("은퇴한 무조건 배지", retiredAt);
		insertBadgeWithCondition("은퇴한 미지원 메트릭 배지", "UNSUPPORTED_METRIC", retiredAt);

		context = startApplication();

		assertThat(context.isActive()).isTrue();
	}

	private ConfigurableApplicationContext startApplication() {
		return new SpringApplicationBuilder(BuyeoOnApplication.class).web(WebApplicationType.SERVLET).run(
				"--server.port=0", "--spring.datasource.url=" + POSTGIS.getJdbcUrl(),
				"--spring.datasource.driver-class-name=org.postgresql.Driver",
				"--spring.datasource.username=" + APPLICATION_USERNAME,
				"--spring.datasource.password=" + APPLICATION_PASSWORD, "--spring.flyway.enabled=true",
				"--spring.jpa.hibernate.ddl-auto=validate",
				"--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect");
	}

	private void insertBadgeWithoutCondition(String name, Instant retiredAt) {
		jdbcTemplate().update(
				"INSERT INTO badges (id, category, name, description, condition_text, retired_at) "
						+ "VALUES (?, 'EXPLORATION'::badge_category, ?, '설명', '조건', ?)",
				UUID.randomUUID(), name, retiredAt == null ? null : Timestamp.from(retiredAt));
	}

	private void insertBadgeWithCondition(String name, String metricKey, Instant retiredAt) {
		UUID badgeId = UUID.randomUUID();
		jdbcTemplate().update(
				"INSERT INTO badges (id, category, name, description, condition_text, retired_at) "
						+ "VALUES (?, 'EXPLORATION'::badge_category, ?, '설명', '조건', ?)",
				badgeId, name, retiredAt == null ? null : Timestamp.from(retiredAt));
		jdbcTemplate().update("INSERT INTO badge_conditions (badge_id, metric_key, threshold) VALUES (?, ?, 1)",
				badgeId, metricKey);
	}

	private JdbcTemplate jdbcTemplate() {
		return new JdbcTemplate(
				new DriverManagerDataSource(POSTGIS.getJdbcUrl(), APPLICATION_USERNAME, APPLICATION_PASSWORD));
	}
}
