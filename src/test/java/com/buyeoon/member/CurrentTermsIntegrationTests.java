package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CurrentTermsIntegrationTests {

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

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM terms");
	}

	/**
	 * 인증하지 않은 사용자가 현재 약관을 조회하면 유형별 최신 시행 버전만 반환하고 이전 버전과 미래 시행 버전은 노출하지 않는다.
	 */
	@Test
	@DisplayName("비로그인 사용자는 유형별 현재 약관만 조회한다")
	void unauthenticatedUserGetsLatestEffectiveTermForEachType() throws Exception {
		UUID oldServiceId = insertTerm("SERVICE", "1.0", true, "이전 서비스 약관", "이전 본문",
				Instant.parse("2026-01-01T00:00:00Z"));
		UUID currentServiceId = insertTerm("SERVICE", "2.0", true, "현재 서비스 약관", "현재 본문",
				Instant.parse("2026-08-01T00:00:00Z"));
		UUID privacyId = insertTerm("PRIVACY", "1.0", true, "개인정보 약관", "개인정보 본문",
				Instant.parse("2026-07-01T00:00:00Z"));
		UUID currentMarketingId = insertTerm("MARKETING", "1.0", false, "마케팅 약관", "마케팅 본문",
				Instant.parse("2026-06-01T00:00:00Z"));
		UUID futureMarketingId = insertTerm("MARKETING", "2.0", false, "미래 마케팅 약관", "미래 본문",
				Instant.parse("2099-01-01T00:00:00Z"));

		// 공개 HTTP seam에서 응답 계약과 DB 현재 시각 기준 필터링을 함께 검증한다.
		mockMvc.perform(get("/terms")).andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items.length()").value(3))
				.andExpect(jsonPath("$.data.items[0].termId").value(currentServiceId.toString()))
				.andExpect(jsonPath("$.data.items[0].type").value("SERVICE"))
				.andExpect(jsonPath("$.data.items[0].version").value("2.0"))
				.andExpect(jsonPath("$.data.items[0].required").value(true))
				.andExpect(jsonPath("$.data.items[0].title").value("현재 서비스 약관"))
				.andExpect(jsonPath("$.data.items[0].content").value("현재 본문"))
				.andExpect(jsonPath("$.data.items[0].effectiveAt").value("2026-08-01T09:00:00+09:00"))
				.andExpect(jsonPath("$.data.items[1].termId").value(privacyId.toString()))
				.andExpect(jsonPath("$.data.items[1].type").value("PRIVACY"))
				.andExpect(jsonPath("$.data.items[2].termId").value(currentMarketingId.toString()))
				.andExpect(jsonPath("$.data.items[2].type").value("MARKETING"))
				.andExpect(jsonPath("$.data.items[?(@.termId == '%s')]", oldServiceId).isEmpty())
				.andExpect(jsonPath("$.data.items[?(@.termId == '%s')]", futureMarketingId).isEmpty());
	}

	/** 같은 유형과 시행 시각을 가진 약관은 버전이 달라도 DB가 거부한다. */
	@Test
	@DisplayName("같은 유형과 시행 시각의 약관은 중복 저장할 수 없다")
	void sameTypeAndEffectiveAtCannotBeInsertedTwice() {
		Instant effectiveAt = Instant.parse("2026-08-01T00:00:00Z");
		insertTerm("SERVICE", "1.0", true, "서비스 약관", "본문", effectiveAt);

		// 애플리케이션을 우회한 입력도 DB 고유 제약이 최종 방어한다.
		assertThatThrownBy(() -> insertTerm("SERVICE", "2.0", true, "새 서비스 약관", "새 본문", effectiveAt))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertTerm(String type, String version, boolean required, String title, String content,
			Instant effectiveAt) {
		UUID termId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO terms (id, type, version, required, title, content, effective_at)
				VALUES (?, ?::term_type, ?, ?, ?, ?, ?)
				""", termId, type, version, required, title, content, Timestamp.from(effectiveAt));
		return termId;
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
}
