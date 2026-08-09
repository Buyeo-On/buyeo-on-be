package com.buyeoon.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.member.entity.MemberEntity;
import com.buyeoon.member.entity.MemberSettingEntity;
import com.buyeoon.member.entity.TermConsentEntity;
import com.buyeoon.member.entity.TermEntity;
import com.buyeoon.member.entity.TermType;
import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.PlaceEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "POSTGIS_TEST_URL", matches = ".+")
class PostgresSchemaIntegrationTests {

	@Autowired
	private EntityManager entityManager;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> requiredEnvironment("POSTGIS_TEST_URL"));
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add(
				"spring.datasource.username",
				() -> requiredEnvironment("POSTGIS_TEST_APPLICATION_USERNAME"));
		registry.add(
				"spring.datasource.password",
				() -> requiredEnvironment("POSTGIS_TEST_APPLICATION_PASSWORD"));
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.flyway.url", () -> requiredEnvironment("POSTGIS_TEST_URL"));
		registry.add(
				"spring.flyway.user", () -> requiredEnvironment("POSTGIS_TEST_FLYWAY_USERNAME"));
		registry.add(
				"spring.flyway.password", () -> requiredEnvironment("POSTGIS_TEST_FLYWAY_PASSWORD"));
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	@Test
	@Transactional
	void migrationAndRepresentativeMappingsWorkAgainstPostgis() {
		MemberEntity member = MemberEntity.create();
		entityManager.persist(member);
		entityManager.flush();

		TermEntity term = TermEntity.create(
				TermType.SERVICE,
				"integration-test-" + UUID.randomUUID(),
				true,
				"Service terms",
				"Test content",
				Instant.now());
		entityManager.persist(term);
		entityManager.flush();

		entityManager.persist(TermConsentEntity.create(member.getId(), term.getId(), true));
		MemberSettingEntity setting = MemberSettingEntity.create(member.getId());
		entityManager.persist(setting);
		entityManager.flush();
		long initialVersion = setting.getVersion();
		setting.update(true, false);

		GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
		PlaceEntity place = PlaceEntity.create(
				PlaceCategory.HERITAGE,
				"Integration place",
				null,
				null,
				null,
				null,
				geometryFactory.createPoint(new Coordinate(126.91, 36.28)),
				null,
				null);
		entityManager.persist(place);

		IdempotencyRequestEntity request = IdempotencyRequestEntity.create(
				member.getId(),
				"integration-" + UUID.randomUUID(),
				"integration-test",
				"hash",
				Instant.now().plus(1, ChronoUnit.HOURS));
		request.complete(200, "{\"success\":true}");
		entityManager.persist(request);
		entityManager.flush();
		assertThat(setting.getVersion()).isGreaterThan(initialVersion);
		entityManager.clear();

		PlaceEntity savedPlace = entityManager.find(PlaceEntity.class, place.getId());
		assertThat(savedPlace.getLocation().getSRID()).isEqualTo(4326);
		assertThat(entityManager.find(IdempotencyRequestEntity.class, request.getId())).isNotNull();
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must be set for the PostGIS integration test");
		}
		return value;
	}
}
