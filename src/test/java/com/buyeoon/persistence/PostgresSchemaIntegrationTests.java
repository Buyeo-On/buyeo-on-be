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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class PostgresSchemaIntegrationTests {

	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** V16이 온보딩 검증용 약관 4종을 명확한 초안 버전으로 적재하는지 검증한다. */
	@Test
	@DisplayName("개발 검증용 약관 4종은 초안 버전과 필수 여부로 시딩된다")
	void draftTermsAreSeeded() {
		assertThat(jdbcTemplate.queryForList("""
				SELECT type::text || '|' || version || '|' || required || '|' || title
				FROM terms
				ORDER BY CASE type
				    WHEN 'SERVICE' THEN 1
				    WHEN 'PRIVACY' THEN 2
				    WHEN 'LOCATION' THEN 3
				    WHEN 'MARKETING' THEN 4
				END
				""", String.class)).containsExactly("SERVICE|0.1-draft|true|서비스 이용약관",
				"PRIVACY|0.1-draft|true|개인정보 수집·이용 동의", "LOCATION|0.1-draft|true|위치기반서비스 이용약관",
				"MARKETING|0.1-draft|false|마케팅 정보 수신 동의");
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM terms
				WHERE content LIKE '%개발 검증용 초안(0.1-draft)%'
				""", Long.class)).isEqualTo(4L);
	}

	/** V15가 앱에서 노출할 군민증 캐릭터와 테마를 승인된 객체 키와 순서로 적재하는지 검증한다. */
	@Test
	@DisplayName("군민증 카탈로그는 승인된 S3 객체 키와 순서로 시딩된다")
	void citizenCardCatalogIsSeeded() {
		assertThat(jdbcTemplate.queryForList("""
				SELECT name || '|' || image_key || '|' || sort_order
				FROM card_characters
				ORDER BY sort_order
				""", String.class)).containsExactly("금동이|public/characters/geumdong.png|1",
				"금용이|public/characters/geumyong.png|2", "금황이|public/characters/geumhwang.png|3");

		assertThat(jdbcTemplate.queryForList("""
				SELECT name || '|' || image_key || '|' || sort_order
				FROM card_themes
				ORDER BY sort_order
				""", String.class)).containsExactly("봉황|public/themes/phoenix.svg|1", "연화문|public/themes/lotus.svg|2",
				"금관|public/themes/crown.svg|3", "금관 장식|public/themes/crown_ornament.svg|4",
				"석탑|public/themes/pagoda.svg|5", "돛배|public/themes/sailboat.svg|6");
	}

	/** V2의 기존 LOCKED 참여 데이터가 최신 스키마로 업그레이드될 때 유실되지 않고 AVAILABLE로 전환되는지 검증한다. */
	@Test
	@DisplayName("V2의 LOCKED 참여 데이터는 업그레이드 후 AVAILABLE로 보존된다")
	void lockedParticipationIsPreservedAsAvailableWhenUpgradingFromV2() throws Exception {
		String schema = "mission_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		String url = POSTGIS.getJdbcUrl();
		String username = POSTGIS.getUsername();
		String password = POSTGIS.getPassword();
		Flyway flyway = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.target(MigrationVersion.fromVersion("2")).cleanDisabled(false).load();

		try {
			flyway.migrate();
			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				connection.setSchema(schema);
				statement.executeUpdate("""
						INSERT INTO members (id) VALUES ('10000000-0000-0000-0000-000000000001');
						INSERT INTO trips (id, member_id)
						VALUES ('10000000-0000-0000-0000-000000000002',
						        '10000000-0000-0000-0000-000000000001');
						INSERT INTO places (id, category, name, location)
						VALUES ('10000000-0000-0000-0000-000000000003', 'HERITAGE', 'Upgrade place',
						        public.ST_SetSRID(public.ST_MakePoint(126.91, 36.28), 4326)::public.geography);
						INSERT INTO missions (id, place_id, type, title, description, reward_points)
						VALUES ('10000000-0000-0000-0000-000000000004',
						        '10000000-0000-0000-0000-000000000003',
						        'MULTIPLE_CHOICE', 'Upgrade mission', 'Choose one', 100);
						INSERT INTO mission_participations (trip_id, mission_id, status)
						VALUES ('10000000-0000-0000-0000-000000000002',
						        '10000000-0000-0000-0000-000000000004', 'LOCKED');
						""");
			}

			Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema).load()
					.migrate();

			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				connection.setSchema(schema);
				try (ResultSet resultSet = statement.executeQuery("SELECT status::text FROM mission_participations")) {
					assertThat(resultSet.next()).isTrue();
					assertThat(resultSet.getString(1)).isEqualTo("AVAILABLE");
				}
			}
		} finally {
			flyway.clean();
		}
	}

	/** V4의 공개 이미지 객체 키가 V5 컬럼 변경 이후에도 보존되는지 검증한다. */
	@Test
	@DisplayName("V4의 public 이미지 객체 키는 V5의 image_key로 보존된다")
	void publicImageKeysArePreservedWhenUpgradingFromV4() throws Exception {
		String schema = "image_key_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		String url = POSTGIS.getJdbcUrl();
		String username = POSTGIS.getUsername();
		String password = POSTGIS.getPassword();
		Flyway flyway = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.target(MigrationVersion.fromVersion("4")).cleanDisabled(false).load();

		try {
			flyway.migrate();
			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				connection.setSchema(schema);
				statement.executeUpdate("""
						INSERT INTO card_characters (name, image_url)
						VALUES ('Character', 'public/characters/character.webp');
						INSERT INTO card_themes (name, image_url)
						VALUES ('Theme', 'public/themes/theme.webp');
						INSERT INTO places (category, name, image_url, location)
						VALUES ('HERITAGE', 'Image place', 'public/places/place.webp',
						        public.ST_SetSRID(public.ST_MakePoint(126.91, 36.28), 4326)::public.geography);
						INSERT INTO badges (category, name, description, image_url, condition_text)
						VALUES ('EXPLORATION', 'Badge', 'Description', 'public/badges/badge.webp', 'Condition');
						""");
			}

			Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
					.target(MigrationVersion.fromVersion("5")).load().migrate();

			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				connection.setSchema(schema);
				try (ResultSet resultSet = statement.executeQuery("""
						SELECT count(*)
						FROM (
						    SELECT image_key FROM card_characters WHERE image_key LIKE 'public/%'
						    UNION ALL
						    SELECT image_key FROM card_themes WHERE image_key LIKE 'public/%'
						    UNION ALL
						    SELECT image_key FROM places WHERE image_key LIKE 'public/%'
						    UNION ALL
						    SELECT image_key FROM badges WHERE image_key LIKE 'public/%'
						) public_images
						""")) {
					assertThat(resultSet.next()).isTrue();
					assertThat(resultSet.getLong(1)).isEqualTo(4L);
				}
			}
		} finally {
			flyway.clean();
		}
	}

	/** 기존 공개 이미지 URL이 남아 있으면 V5 전체가 원자적으로 실패하는지 검증한다. */
	@Test
	@DisplayName("V4에 공개 이미지 URL이 남아 있으면 V5를 적용하지 않는다")
	void legacyPublicImageUrlRejectsV5Atomically() throws Exception {
		String schema = "image_url_rejection_" + UUID.randomUUID().toString().replace("-", "");
		String url = POSTGIS.getJdbcUrl();
		String username = POSTGIS.getUsername();
		String password = POSTGIS.getPassword();
		Flyway flyway = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.target(MigrationVersion.fromVersion("4")).cleanDisabled(false).load();

		try {
			flyway.migrate();
			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				connection.setSchema(schema);
				statement.executeUpdate("""
						INSERT INTO places (category, name, image_url, location)
						VALUES ('HERITAGE', 'Legacy image place', 'https://example.com/place.webp',
						        public.ST_SetSRID(public.ST_MakePoint(126.91, 36.28), 4326)::public.geography)
						""");
			}

			Flyway latest = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
					.load();
			assertThatThrownBy(latest::migrate)
					.hasMessageContaining("Public image URLs must be replaced with public/ S3 object keys before V5");

			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				connection.setSchema(schema);
				try (ResultSet resultSet = statement.executeQuery("""
						SELECT
						    count(*) FILTER (WHERE column_name = 'image_url') AS image_url_columns,
						    count(*) FILTER (WHERE column_name = 'image_key') AS image_key_columns
						FROM information_schema.columns
						WHERE table_schema = current_schema()
						  AND table_name IN ('card_characters', 'card_themes', 'places', 'badges')
						""")) {
					assertThat(resultSet.next()).isTrue();
					assertThat(resultSet.getLong("image_url_columns")).isEqualTo(4L);
					assertThat(resultSet.getLong("image_key_columns")).isZero();
				}
				String appliedMigrationCountQuery = """
						SELECT count(*)
						FROM flyway_schema_history
						WHERE version = '5' AND success
						""";
				try (ResultSet resultSet = statement.executeQuery(appliedMigrationCountQuery)) {
					assertThat(resultSet.next()).isTrue();
					assertThat(resultSet.getLong(1)).isZero();
				}
			}
		} finally {
			flyway.clean();
		}
	}

	/** V12가 허용했던 0포인트 양수 선택을 최신 계약의 NO_POINTS로 보존하는지 검증한다. */
	@Test
	@DisplayName("V12의 0포인트 정산은 업그레이드 후 NO_POINTS로 정규화된다")
	void zeroPointSettlementsAreNormalizedWhenUpgradingFromV12() throws Exception {
		String schema = "point_settlement_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		String url = POSTGIS.getJdbcUrl();
		String username = POSTGIS.getUsername();
		String password = POSTGIS.getPassword();
		Flyway flyway = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.target(MigrationVersion.fromVersion("12")).cleanDisabled(false).load();

		try {
			flyway.migrate();
			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				connection.setSchema(schema);
				statement.executeUpdate("""
						INSERT INTO members (id)
						VALUES ('10000000-0000-0000-0000-000000000001');
						INSERT INTO trips (id, member_id, status, ended_at)
						VALUES ('10000000-0000-0000-0000-000000000002',
						        '10000000-0000-0000-0000-000000000001', 'ENDED',
						        TIMESTAMPTZ '2026-08-20 00:00:00Z'),
						       ('10000000-0000-0000-0000-000000000003',
						        '10000000-0000-0000-0000-000000000001', 'ENDED',
						        TIMESTAMPTZ '2026-08-20 00:00:00Z');
						INSERT INTO point_settlements (trip_id, choice, settled_points, settled_at, expires_at)
						VALUES ('10000000-0000-0000-0000-000000000002', 'LEAVE_TO_BUYEO', 0,
						        TIMESTAMPTZ '2026-08-20 00:00:00Z', NULL),
						       ('10000000-0000-0000-0000-000000000003', 'CARRY_OVER', 0,
						        TIMESTAMPTZ '2026-08-20 00:00:00Z', TIMESTAMPTZ '2026-08-30 00:00:00Z');
						""");
			}

			Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema).load()
					.migrate();

			try (Connection connection = DriverManager.getConnection(url, username, password);
					Statement statement = connection.createStatement()) {
				connection.setSchema(schema);
				try (ResultSet resultSet = statement.executeQuery("""
						SELECT count(*)
						FROM point_settlements
						WHERE choice = 'NO_POINTS'
						  AND settled_points = 0
						  AND expires_at IS NULL
						  AND expired_at IS NULL
						""")) {
					assertThat(resultSet.next()).isTrue();
					assertThat(resultSet.getLong(1)).isEqualTo(2L);
				}
			}
		} finally {
			flyway.clean();
		}
	}

	/** 실제 PostgreSQL enum에서 위치에 따라 계산되는 상태가 제거되고 영속 상태만 남았는지 검증한다. */
	@Test
	@DisplayName("DB 미션 상태 enum에는 위치와 무관한 영속 상태만 존재한다")
	void persistentMissionStatusOnlyContainsNonLocationStates() {
		assertThat(jdbcTemplate.queryForList("""
				SELECT enum_value.enumlabel
				FROM pg_enum enum_value
				JOIN pg_type enum_type ON enum_type.oid = enum_value.enumtypid
				JOIN pg_namespace namespace ON namespace.oid = enum_type.typnamespace
				WHERE enum_type.typname = 'mission_status'
				  AND namespace.nspname = current_schema()
				ORDER BY enum_value.enumsortorder
				""", String.class)).containsExactly("AVAILABLE", "EXHAUSTED", "COMPLETED");
	}

	/** Flyway와 애플리케이션이 운영과 같은 비관리자 DB 계정을 공유하는지 검증한다. */
	@Test
	@DisplayName("Flyway와 애플리케이션은 같은 비관리자 DB 계정을 사용한다")
	void migrationAndApplicationUseSameDatabaseAccount() {
		assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APPLICATION_USERNAME);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT tableowner
				FROM pg_tables
				WHERE schemaname = current_schema() AND tablename = 'members'
				""", String.class)).isEqualTo(APPLICATION_USERNAME);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT rolsuper
				FROM pg_roles
				WHERE rolname = current_user
				""", Boolean.class)).isFalse();
	}

	/** 실제 마이그레이션 DB가 정산 선택별 포인트·만료 계약을 강제하는지 검증한다. */
	@Test
	@DisplayName("DB는 유효한 세 정산 선택만 허용하고 양수 정산의 0포인트를 거부한다")
	void pointSettlementChoicesEnforcePointsAndExpirationContract() {
		UUID memberId = UUID.randomUUID();
		UUID leaveTripId = UUID.randomUUID();
		UUID carryTripId = UUID.randomUUID();
		UUID noPointsTripId = UUID.randomUUID();
		UUID zeroLeaveTripId = UUID.randomUUID();
		UUID zeroCarryTripId = UUID.randomUUID();
		Instant settledAt = Instant.parse("2026-08-20T00:00:00Z");

		jdbcTemplate.update("INSERT INTO members (id) VALUES (?)", memberId);
		for (UUID tripId : new UUID[]{leaveTripId, carryTripId, noPointsTripId, zeroLeaveTripId, zeroCarryTripId}) {
			jdbcTemplate.update("""
					INSERT INTO trips (id, member_id, status, ended_at)
					VALUES (?, ?, 'ENDED', ?)
					""", tripId, memberId, Timestamp.from(settledAt));
		}

		try {
			jdbcTemplate.update("""
					INSERT INTO point_settlements (trip_id, choice, settled_points, settled_at)
					VALUES (?, 'LEAVE_TO_BUYEO', 100, ?)
					""", leaveTripId, Timestamp.from(settledAt));
			jdbcTemplate.update("""
					INSERT INTO point_settlements (trip_id, choice, settled_points, settled_at, expires_at)
					VALUES (?, 'CARRY_OVER', 100, ?, ?)
					""", carryTripId, Timestamp.from(settledAt), Timestamp.from(settledAt.plus(240, ChronoUnit.HOURS)));
			jdbcTemplate.update("""
					INSERT INTO point_settlements (trip_id, choice, settled_points, settled_at)
					VALUES (?, 'NO_POINTS', 0, ?)
					""", noPointsTripId, Timestamp.from(settledAt));

			assertThatThrownBy(() -> jdbcTemplate.update("""
					INSERT INTO point_settlements (trip_id, choice, settled_points, settled_at)
					VALUES (?, 'LEAVE_TO_BUYEO', 0, ?)
					""", zeroLeaveTripId, Timestamp.from(settledAt)))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbcTemplate.update("""
					INSERT INTO point_settlements (trip_id, choice, settled_points, settled_at, expires_at)
					VALUES (?, 'CARRY_OVER', 0, ?, ?)
					""", zeroCarryTripId, Timestamp.from(settledAt),
					Timestamp.from(settledAt.plus(240, ChronoUnit.HOURS))))
					.isInstanceOf(DataIntegrityViolationException.class);
		} finally {
			jdbcTemplate.update("DELETE FROM members WHERE id = ?", memberId);
		}
	}

	/** 애플리케이션 검증을 우회한 입력도 DB가 유형별 시도 횟수 규칙에 따라 최종적으로 방어하는지 검증한다. */
	@Test
	@DisplayName("DB는 제한된 사진 미션을 거부하고 유효한 퀴즈 제한을 허용한다")
	void databaseRejectsLimitedPhotoMissionAndAllowsValidQuizLimits() {
		UUID placeId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO places (id, category, name, location)
				VALUES (?, 'HERITAGE', 'Constraint test place',
				        ST_SetSRID(ST_MakePoint(126.91, 36.28), 4326)::geography)
				""", placeId);

		try {
			assertThatThrownBy(() -> jdbcTemplate.update("""
					INSERT INTO missions
					    (place_id, type, title, description, reward_points, max_attempts)
					VALUES (?, 'PHOTO', 'Photo', 'Take a photo', 100, 1)
					""", placeId)).isInstanceOf(DataIntegrityViolationException.class);

			jdbcTemplate.update("""
					INSERT INTO missions
					    (place_id, type, title, description, reward_points, max_attempts)
					VALUES (?, 'MULTIPLE_CHOICE', 'Multiple choice', 'Choose one', 100, 3)
					""", placeId);
			jdbcTemplate.update("""
					INSERT INTO missions
					    (place_id, type, title, description, reward_points, max_attempts,
					     ox_correct_answer)
					VALUES (?, 'OX', 'OX', 'True or false', 100, NULL, true)
					""", placeId);

			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM missions WHERE place_id = ?", Long.class,
					placeId)).isEqualTo(2L);
		} finally {
			jdbcTemplate.update("DELETE FROM missions WHERE place_id = ?", placeId);
			jdbcTemplate.update("DELETE FROM places WHERE id = ?", placeId);
		}
	}

	/** 공개 이미지 값이 임시 URL이 아니라 public prefix의 S3 객체 키로 제한되는지 검증한다. */
	@Test
	@DisplayName("DB는 공개 이미지에 public prefix의 객체 키만 허용한다")
	void publicImageKeysRequirePublicPrefix() {
		UUID validPlaceId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO places (category, name, image_key, location)
				VALUES ('HERITAGE', 'Invalid image place', 'https://example.com/image.webp',
				        ST_SetSRID(ST_MakePoint(126.91, 36.28), 4326)::geography)
				""")).isInstanceOf(DataIntegrityViolationException.class);

		try {
			jdbcTemplate.update("""
					INSERT INTO places (id, category, name, image_key, location)
					VALUES (?, 'HERITAGE', 'Valid image place', 'public/places/valid.webp',
					        ST_SetSRID(ST_MakePoint(126.91, 36.28), 4326)::geography)
					""", validPlaceId);
			assertThat(jdbcTemplate.queryForObject("SELECT image_key FROM places WHERE id = ?", String.class,
					validPlaceId)).isEqualTo("public/places/valid.webp");
		} finally {
			jdbcTemplate.update("DELETE FROM places WHERE id = ?", validPlaceId);
		}
	}

	/** 버전 관리되는 샘플 데이터가 모든 미션 유형과 정렬 가능한 객관식 선택지를 제공하는지 검증한다. */
	@Test
	@DisplayName("샘플 카탈로그에는 모든 미션 유형과 정렬 가능한 선택지가 있다")
	void versionedSampleCatalogContainsEveryMissionTypeAndSortableChoices() {
		for (String type : new String[]{"OX", "MULTIPLE_CHOICE", "PHOTO"}) {
			assertThat(jdbcTemplate.queryForObject("""
					SELECT count(*)
					FROM missions mission
					JOIN places place ON place.id = mission.place_id
					WHERE place.source_name = 'BUYEO_ON_SAMPLE' AND mission.type::text = ?
					""", Long.class, type)).isPositive();
		}

		assertThat(jdbcTemplate.queryForObject("""
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
				""", Boolean.class)).isTrue();
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

	/** 실제 PostGIS에서 대표 엔티티의 저장·재조회, 공간 타입, 낙관적 잠금이 함께 동작하는지 검증한다. */
	@Test
	@DisplayName("Flyway 마이그레이션과 대표 JPA 매핑이 PostGIS에서 동작한다")
	@Transactional
	void migrationAndRepresentativeMappingsWorkAgainstPostgis() {
		MemberEntity member = MemberEntity.create();
		entityManager.persist(member);
		entityManager.flush();

		TermEntity term = TermEntity.create(TermType.SERVICE, "integration-test-" + UUID.randomUUID(), true,
				"Service terms", "Test content", Instant.now());
		entityManager.persist(term);
		entityManager.flush();

		entityManager.persist(TermConsentEntity.create(member.getId(), term.getId(), true));
		MemberSettingEntity setting = MemberSettingEntity.create(member.getId());
		entityManager.persist(setting);
		entityManager.flush();
		long initialVersion = setting.getVersion();
		setting.update(true, false);

		GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
		PlaceEntity place = PlaceEntity.create(PlaceCategory.HERITAGE, "Integration place", null, null, null, null,
				geometryFactory.createPoint(new Coordinate(126.91, 36.28)), "KTO_TOUR_API", "3310483", null);
		entityManager.persist(place);

		IdempotencyRequestEntity request = IdempotencyRequestEntity.create(member.getId(),
				"integration-" + UUID.randomUUID(), "integration-test", "hash",
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

	/** 같은 제공처가 부여한 동일한 장소 식별자로 중복 장소를 저장하지 못하도록 보장한다. */
	@Test
	@DisplayName("장소 외부 식별자는 제공처 안에서 유일하다")
	@Transactional
	void placeExternalIdentityIsUniqueWithinItsSource() {
		String externalId = UUID.randomUUID().toString();
		entityManager.persist(createPlace("KTO_TOUR_API", externalId));
		entityManager.flush();

		entityManager.persist(createPlace("KTO_TOUR_API", externalId));

		assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
	}

	/** 외부 식별자의 출처를 해석할 수 있도록 externalId가 있으면 sourceName도 필수임을 보장한다. */
	@Test
	@DisplayName("장소 외부 식별자가 있으면 제공처도 반드시 있어야 한다")
	@Transactional
	void placeExternalIdentityRequiresItsSource() {
		entityManager.persist(createPlace(null, UUID.randomUUID().toString()));

		assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
	}

	private PlaceEntity createPlace(String sourceName, String externalId) {
		GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
		return PlaceEntity.create(PlaceCategory.HERITAGE, "Integration place " + UUID.randomUUID(), null, null, null,
				null, geometryFactory.createPoint(new Coordinate(126.91, 36.28)), sourceName, externalId, null);
	}

}
