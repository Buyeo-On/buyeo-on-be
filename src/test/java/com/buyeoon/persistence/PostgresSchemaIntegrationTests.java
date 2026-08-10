package com.buyeoon.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.member.entity.MemberEntity;
import com.buyeoon.member.entity.MemberSettingEntity;
import com.buyeoon.member.entity.TermConsentEntity;
import com.buyeoon.member.entity.TermEntity;
import com.buyeoon.member.entity.TermType;
import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.PlaceEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "POSTGIS_TEST_URL", matches = ".+")
class PostgresSchemaIntegrationTests {

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void lockedParticipationIsPreservedAsAvailableWhenUpgradingFromV2() throws Exception {
		String schema = "mission_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		String url = requiredEnvironment("POSTGIS_TEST_URL");
		String username = requiredEnvironment("POSTGIS_TEST_FLYWAY_USERNAME");
		String password = requiredEnvironment("POSTGIS_TEST_FLYWAY_PASSWORD");
		Flyway flyway = Flyway.configure()
				.dataSource(url, username, password)
				.schemas(schema)
				.defaultSchema(schema)
				.target(MigrationVersion.fromVersion("2"))
				.load();

		try {
			flyway.migrate();
			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				statement.execute("SET search_path TO " + schema + ", public");
				statement.executeUpdate("""
						INSERT INTO members (id) VALUES ('10000000-0000-0000-0000-000000000001');
						INSERT INTO trips (id, member_id)
						VALUES ('10000000-0000-0000-0000-000000000002',
						        '10000000-0000-0000-0000-000000000001');
						INSERT INTO places (id, category, name, location)
						VALUES ('10000000-0000-0000-0000-000000000003', 'HERITAGE', 'Upgrade place',
						        ST_SetSRID(ST_MakePoint(126.91, 36.28), 4326)::geography);
						INSERT INTO missions (id, place_id, type, title, description, reward_points)
						VALUES ('10000000-0000-0000-0000-000000000004',
						        '10000000-0000-0000-0000-000000000003',
						        'MULTIPLE_CHOICE', 'Upgrade mission', 'Choose one', 100);
						INSERT INTO mission_participations (trip_id, mission_id, status)
						VALUES ('10000000-0000-0000-0000-000000000002',
						        '10000000-0000-0000-0000-000000000004', 'LOCKED');
						""");
			}

			Flyway.configure()
					.dataSource(url, username, password)
					.schemas(schema)
					.defaultSchema(schema)
					.load()
					.migrate();

			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				statement.execute("SET search_path TO " + schema + ", public");
				try (ResultSet resultSet = statement.executeQuery(
						"SELECT status::text FROM mission_participations")) {
					assertThat(resultSet.next()).isTrue();
					assertThat(resultSet.getString(1)).isEqualTo("AVAILABLE");
				}
			}
		} finally {
			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
			}
		}
	}

	@Test
	void persistentMissionStatusOnlyContainsNonLocationStates() {
		assertThat(jdbcTemplate.queryForList(
				"""
				SELECT enum_value.enumlabel
				FROM pg_enum enum_value
				JOIN pg_type enum_type ON enum_type.oid = enum_value.enumtypid
				JOIN pg_namespace namespace ON namespace.oid = enum_type.typnamespace
				WHERE enum_type.typname = 'mission_status'
				  AND namespace.nspname = current_schema()
				ORDER BY enum_value.enumsortorder
				""",
				String.class))
				.containsExactly("AVAILABLE", "EXHAUSTED", "COMPLETED");
	}

	@Test
	void databaseRejectsLimitedPhotoMissionAndAllowsValidQuizLimits() {
		UUID placeId = UUID.randomUUID();
		jdbcTemplate.update(
				"""
				INSERT INTO places (id, category, name, location)
				VALUES (?, 'HERITAGE', 'Constraint test place',
				        ST_SetSRID(ST_MakePoint(126.91, 36.28), 4326)::geography)
				""",
				placeId);

		try {
			assertThatThrownBy(() -> jdbcTemplate.update(
						"""
						INSERT INTO missions
						    (place_id, type, title, description, reward_points, max_attempts)
						VALUES (?, 'PHOTO', 'Photo', 'Take a photo', 100, 1)
						""",
						placeId))
					.isInstanceOf(DataIntegrityViolationException.class);

			jdbcTemplate.update(
					"""
					INSERT INTO missions
					    (place_id, type, title, description, reward_points, max_attempts)
					VALUES (?, 'MULTIPLE_CHOICE', 'Multiple choice', 'Choose one', 100, 3)
					""",
					placeId);
			jdbcTemplate.update(
					"""
					INSERT INTO missions
					    (place_id, type, title, description, reward_points, max_attempts,
					     ox_correct_answer)
					VALUES (?, 'OX', 'OX', 'True or false', 100, NULL, true)
					""",
					placeId);

			assertThat(jdbcTemplate.queryForObject(
						"SELECT count(*) FROM missions WHERE place_id = ?", Long.class, placeId))
					.isEqualTo(2L);
		} finally {
			jdbcTemplate.update("DELETE FROM missions WHERE place_id = ?", placeId);
			jdbcTemplate.update("DELETE FROM places WHERE id = ?", placeId);
		}
	}

	@Test
	void versionedSampleCatalogContainsEveryMissionTypeAndSortableChoices() {
		for (String type : new String[] {"OX", "MULTIPLE_CHOICE", "PHOTO"}) {
			assertThat(jdbcTemplate.queryForObject(
						"""
						SELECT count(*)
						FROM missions mission
						JOIN places place ON place.id = mission.place_id
						WHERE place.source_name = 'BUYEO_ON_SAMPLE' AND mission.type::text = ?
						""",
						Long.class,
						type))
					.isPositive();
		}

		assertThat(jdbcTemplate.queryForObject(
				"""
				SELECT bool_and(choice_count >= 2 AND choice_count = distinct_sort_order_count)
				FROM (
				    SELECT mission.id,
				           count(choice.id) AS choice_count,
				           count(DISTINCT choice.sort_order) AS distinct_sort_order_count
				    FROM missions mission
				    JOIN places place ON place.id = mission.place_id
				    LEFT JOIN mission_choices choice ON choice.mission_id = mission.id
				    WHERE place.source_name = 'BUYEO_ON_SAMPLE'
				      AND mission.type = 'MULTIPLE_CHOICE'
				    GROUP BY mission.id
				) sample_mission
				""",
				Boolean.class))
				.isTrue();
	}

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
				"KTO_TOUR_API",
				"3310483",
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
		assertThat(savedPlace.getExternalId()).isEqualTo("3310483");
		assertThat(entityManager.find(IdempotencyRequestEntity.class, request.getId())).isNotNull();
	}

	@Test
	@Transactional
	void placeExternalIdentityIsUniqueWithinItsSource() {
		String externalId = UUID.randomUUID().toString();
		entityManager.persist(createPlace("KTO_TOUR_API", externalId));
		entityManager.flush();

		entityManager.persist(createPlace("KTO_TOUR_API", externalId));

		assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
	}

	@Test
	@Transactional
	void placeExternalIdentityRequiresItsSource() {
		entityManager.persist(createPlace(null, UUID.randomUUID().toString()));

		assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
	}

	private PlaceEntity createPlace(String sourceName, String externalId) {
		GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
		return PlaceEntity.create(
				PlaceCategory.HERITAGE,
				"Integration place " + UUID.randomUUID(),
				null,
				null,
				null,
				null,
				geometryFactory.createPoint(new Coordinate(126.91, 36.28)),
				sourceName,
				externalId,
				null);
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must be set for the PostGIS integration test");
		}
		return value;
	}
}
