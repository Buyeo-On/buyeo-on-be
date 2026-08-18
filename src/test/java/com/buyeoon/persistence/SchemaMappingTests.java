package com.buyeoon.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

class SchemaMappingTests {

	private static final Path SCHEMA_SOURCE = Path.of("docs/raw/db-schema.sql");
	private static final Path INITIAL_MIGRATION = Path.of("src/main/resources/db/migration/V1__initial_schema.sql");
	private static final Path PLACE_EXTERNAL_ID_MIGRATION = Path
			.of("src/main/resources/db/migration/V2__add_place_external_id.sql");
	private static final Path MISSION_CONSTRAINTS_MIGRATION = Path
			.of("src/main/resources/db/migration/V3__align_mission_constraints.sql");
	private static final Path PUBLIC_IMAGE_KEYS_MIGRATION = Path
			.of("src/main/resources/db/migration/V5__replace_public_image_urls_with_keys.sql");
	private static final Path CITIZEN_CARD_CONSTRAINTS_MIGRATION = Path
			.of("src/main/resources/db/migration/V7__add_citizen_card_constraints.sql");
	private static final Path MEMBER_PURGE_MIGRATION = Path
			.of("src/main/resources/db/migration/V8__track_member_data_purge.sql");
	private static final Path LOCATION_TERM_MIGRATION = Path
			.of("src/main/resources/db/migration/V9__add_location_term_type.sql");
	private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE ([a-z_]+) ");

	/** 초기 스키마에 후속 마이그레이션을 적용한 정의가 기준 DB 스키마와 같음을 보장한다. */
	@Test
	@DisplayName("기준 DB 스키마는 Flyway 마이그레이션 체인의 최종 상태와 일치한다")
	void canonicalSchemaMatchesMigrationChain() throws IOException {
		String baseline = Files.readString(INITIAL_MIGRATION, StandardCharsets.UTF_8);
		String canonicalSchema = Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8);
		String legacyTermType = "CREATE TYPE term_type AS ENUM ('SERVICE', 'PRIVACY', 'MARKETING');";
		String currentTermType = "CREATE TYPE term_type AS ENUM ('SERVICE', 'PRIVACY', 'LOCATION', 'MARKETING');";
		String legacyMissionStatus = "CREATE TYPE mission_status AS ENUM ('LOCKED', 'AVAILABLE', 'EXHAUSTED', 'COMPLETED');";
		String currentMissionStatus = "CREATE TYPE mission_status AS ENUM ('AVAILABLE', 'EXHAUSTED', 'COMPLETED');";
		String placeSourceColumns = String.join("\n", "    source_name text, -- 관광데이터 제공처",
				"    source_url text -- 관광데이터 원문 URL", "");
		String placeExternalIdentityColumns = String.join("\n", "    source_name text, -- 관광데이터 제공처",
				"    external_id text, -- 제공처가 부여한 장소 식별자", "    source_url text, -- 관광데이터 원문 URL",
				"    CHECK (external_id IS NULL OR source_name IS NOT NULL)", "");
		String placeLocationIndex = "CREATE INDEX places_location_gix ON places USING GIST (location);";
		String placeIndexes = String.join("\n", "CREATE INDEX places_location_gix ON places USING GIST (location);",
				"CREATE UNIQUE INDEX places_source_external_id_uq", "    ON places (source_name, external_id)",
				"    WHERE external_id IS NOT NULL;");
		String legacyMissionConstraints = String.join("\n", "    ox_correct_answer boolean, -- OX 미션 정답", "    CHECK (",
				"        (type = 'OX' AND ox_correct_answer IS NOT NULL)",
				"        OR (type <> 'OX' AND ox_correct_answer IS NULL)", "    )", ");");
		String currentMissionConstraints = String.join("\n", "    ox_correct_answer boolean, -- OX 미션 정답",
				"    CHECK (", "        (type = 'OX' AND ox_correct_answer IS NOT NULL)",
				"        OR (type <> 'OX' AND ox_correct_answer IS NULL)", "    ),",
				"    CHECK (type <> 'PHOTO' OR max_attempts IS NULL)", ");");
		String imageKeyConstraint = "image_key text NOT NULL CHECK (image_key LIKE 'public/%'),";
		String characterImageKeyColumn = imageKeyConstraint + " -- 캐릭터 이미지 객체 키";
		String themeImageKeyColumn = imageKeyConstraint + " -- 테마 이미지 객체 키";
		String sortOrderColumn = "    sort_order smallint NOT NULL UNIQUE CHECK (sort_order > 0)";
		String displayNameConstraintEnd = "    )," + " -- 앞뒤 공백과 제어문자가 없는 표시 이름";
		String legacyMemberLifecycle = String.join("\n", "    purge_after timestamptz, -- 개인정보 파기 기한", "    CHECK (",
				"        (status = 'ACTIVE' AND withdrawn_at IS NULL AND purge_after IS NULL)",
				"        OR (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL AND purge_after IS NOT NULL)", "    )");
		String currentMemberLifecycle = String.join("\n", "    purge_after timestamptz, -- 개인정보 파기 기한",
				"    purged_at timestamptz, -- 개인정보 파기 완료 시각", "    CHECK (",
				"        (status = 'ACTIVE' AND withdrawn_at IS NULL AND purge_after IS NULL AND purged_at IS NULL)",
				"        OR (", "            status = 'WITHDRAWN'", "            AND withdrawn_at IS NOT NULL",
				"            AND purge_after IS NOT NULL",
				"            AND (purged_at IS NULL OR purged_at >= withdrawn_at)", "        )", "    )");
		String authSessionIndex = "CREATE INDEX auth_sessions_member_idx ON auth_sessions (member_id, expires_at);";
		String memberPurgeIndexes = String.join("\n", authSessionIndex, "CREATE INDEX members_due_purge_idx",
				"    ON members (purge_after, id)", "    WHERE status = 'WITHDRAWN' AND purged_at IS NULL;");
		String privatePhotoObjectKey = "object_key text NOT NULL UNIQUE CHECK (object_key LIKE 'private/%'),"
				+ " -- 비공개 스토리지 객체 키";
		String publicImageKeySchema = baseline
				.replace("expires_at timestamptz NOT NULL, -- 키 보관 만료 시각",
						"expires_at timestamptz NOT NULL, -- 최초 성공 확정 시각부터 24시간인 키 보관 만료 시각")
				.replace("    UNIQUE (type, version)\n);",
						"    UNIQUE (type, version),\n    UNIQUE (type, effective_at)\n);")
				.replace("image_url text NOT NULL -- 캐릭터 이미지 URL",
						String.join("\n", characterImageKeyColumn, sortOrderColumn + " -- 화면 표시 순서"))
				.replace("image_url text NOT NULL -- 테마 이미지 URL",
						String.join("\n", themeImageKeyColumn, sortOrderColumn + " -- 화면 표시 순서"))
				.replace("image_url text, -- 대표 이미지 URL",
						"image_key text CHECK (image_key IS NULL OR image_key LIKE 'public/%'), -- 대표 이미지 객체 키")
				.replace("image_url text, -- 배지 이미지 URL",
						"image_key text CHECK (image_key IS NULL OR image_key LIKE 'public/%'), -- 배지 이미지 객체 키")
				.replace(
						"display_name varchar(8) NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 8),"
								+ " -- 표시 이름",
						String.join("\n", "display_name varchar(8) NOT NULL CHECK (",
								"        char_length(display_name) BETWEEN 1 AND 8",
								"        AND display_name = btrim(display_name)",
								"        AND display_name !~ '[[:cntrl:]]'", displayNameConstraintEnd))
				.replace("barcode_value text NOT NULL UNIQUE, -- 시연용 바코드 값",
						String.join("\n", "barcode_value text NOT NULL UNIQUE CHECK (",
								"        barcode_value ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}"
										+ "-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'",
								"    ), -- UUID 형식의 시연용 바코드 값"))
				.replace(legacyMemberLifecycle, currentMemberLifecycle)
				.replace("object_key text NOT NULL UNIQUE, -- 스토리지 객체 키", privatePhotoObjectKey)
				.replace(authSessionIndex, memberPurgeIndexes);

		assertThat(baseline).contains(placeSourceColumns).contains(placeLocationIndex).contains(legacyMissionStatus)
				.contains(legacyMissionConstraints).contains(legacyTermType);
		assertThat(publicImageKeySchema.replace(placeSourceColumns, placeExternalIdentityColumns)
				.replace(placeLocationIndex, placeIndexes).replace(legacyMissionStatus, currentMissionStatus)
				.replace(legacyMissionConstraints, currentMissionConstraints).replace(legacyTermType, currentTermType))
				.isEqualTo(canonicalSchema);
		assertThat(Files.readString(LOCATION_TERM_MIGRATION, StandardCharsets.UTF_8))
				.contains("ALTER TYPE term_type ADD VALUE 'LOCATION' AFTER 'PRIVACY'");
	}

	/** 탈퇴 회원 파기 완료 상태와 대상 조회 인덱스가 기준 스키마와 업그레이드 경로에 함께 존재하는지 검증한다. */
	@Test
	@DisplayName("회원 정보 파기 완료 상태는 기준 스키마와 마이그레이션에 정의되어 있다")
	void memberPurgeCompletionIsDefinedInSchemaAndMigration() throws IOException {
		String canonicalSchema = Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8);
		String migration = Files.readString(MEMBER_PURGE_MIGRATION, StandardCharsets.UTF_8);

		assertThat(canonicalSchema).contains("purged_at timestamptz").contains("members_due_purge_idx")
				.contains("purged_at IS NULL");
		assertThat(migration).contains("ADD COLUMN purged_at timestamptz").contains("members_lifecycle_ck")
				.contains("members_due_purge_idx").contains("mission_photos_object_key_prefix_ck");
	}

	/** 미션 상태와 시도 횟수 제약이 신규 설치용 스키마와 기존 DB 업그레이드에 모두 반영됐는지 검증한다. */
	@Test
	@DisplayName("미션 제약조건은 기준 스키마와 마이그레이션에 모두 정의되어 있다")
	void missionConstraintsAreDefinedInSchemaAndMigration() throws IOException {
		String canonicalSchema = Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8);
		String migration = Files.readString(MISSION_CONSTRAINTS_MIGRATION, StandardCharsets.UTF_8);

		assertThat(canonicalSchema)
				.contains("CREATE TYPE mission_status AS ENUM ('AVAILABLE', 'EXHAUSTED', 'COMPLETED')")
				.contains("CHECK (type <> 'PHOTO' OR max_attempts IS NULL)");
		assertThat(migration).contains("SET status = 'AVAILABLE'").contains("WHERE status = 'LOCKED'")
				.contains("CREATE TYPE mission_status AS ENUM ('AVAILABLE', 'EXHAUSTED', 'COMPLETED')")
				.contains("CHECK (type <> 'PHOTO' OR max_attempts IS NULL)");
	}

	/** 장소의 외부 식별자 필수 조건과 제공처 범위 유일성이 모든 DB 생성 경로에 존재하는지 검증한다. */
	@Test
	@DisplayName("장소 외부 식별자 규칙은 기준 스키마와 마이그레이션에 모두 정의되어 있다")
	void placeExternalIdentityIsDefinedInSchemaAndMigration() throws IOException {
		String canonicalSchema = Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8);
		String migration = Files.readString(PLACE_EXTERNAL_ID_MIGRATION, StandardCharsets.UTF_8);

		assertThat(canonicalSchema).contains("external_id text")
				.contains("CHECK (external_id IS NULL OR source_name IS NOT NULL)")
				.contains("CREATE UNIQUE INDEX places_source_external_id_uq")
				.contains("ON places (source_name, external_id)").contains("WHERE external_id IS NOT NULL");
		assertThat(migration).contains("ADD COLUMN external_id text")
				.contains("CHECK (external_id IS NULL OR source_name IS NOT NULL)")
				.contains("CREATE UNIQUE INDEX places_source_external_id_uq")
				.contains("ON places (source_name, external_id)").contains("WHERE external_id IS NOT NULL");
	}

	/** 공개 콘텐츠의 영속 값이 만료 URL이 아니라 S3 객체 키로 저장되는지 검증한다. */
	@Test
	@DisplayName("공개 콘텐츠 이미지는 URL 대신 S3 객체 키로 저장한다")
	void publicImagesUseStableObjectKeys() throws IOException {
		String canonicalSchema = Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8);
		String migration = Files.readString(PUBLIC_IMAGE_KEYS_MIGRATION, StandardCharsets.UTF_8);

		assertThat(canonicalSchema).doesNotContain("image_url").contains("image_key text")
				.contains("image_key LIKE 'public/%'");
		assertThat(migration).contains("RENAME COLUMN image_url TO image_key")
				.contains("Public image URLs must be replaced with public/ S3 object keys before V5")
				.contains("CHECK (image_key LIKE 'public/%')");
	}

	/** 군민증 카탈로그, 표시 이름과 바코드 제약이 기준 스키마와 업그레이드 경로에 함께 존재하는지 검증한다. */
	@Test
	@DisplayName("군민증 발급 기반 제약은 기준 스키마와 마이그레이션에 정의되어 있다")
	void citizenCardConstraintsAreDefinedInSchemaAndMigration() throws IOException {
		String canonicalSchema = Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8);
		String migration = Files.readString(CITIZEN_CARD_CONSTRAINTS_MIGRATION, StandardCharsets.UTF_8);

		assertThat(canonicalSchema).contains("sort_order smallint NOT NULL UNIQUE CHECK (sort_order > 0)")
				.contains("display_name = btrim(display_name)").contains("citizen_cards")
				.contains("barcode_value ~ '^[0-9a-f]");
		assertThat(migration).contains("card_characters_sort_order_uq").contains("member_profiles_display_name_ck")
				.contains("citizen_cards_barcode_value_ck");
	}

	/** 기준 스키마의 테이블과 JPA 엔티티가 누락이나 잉여 매핑 없이 일대일로 대응함을 보장한다. */
	@Test
	@DisplayName("기준 스키마의 모든 테이블에는 대응하는 JPA 엔티티가 있다")
	void everyTableHasAnEntityMapping() throws ClassNotFoundException, IOException {
		Set<String> schemaTables = schemaTables();
		Set<String> entityTables = entityTables();

		assertThat(entityTables).containsExactlyInAnyOrderElementsOf(schemaTables);
	}

	private Set<String> schemaTables() throws IOException {
		Matcher matcher = CREATE_TABLE.matcher(Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8));
		return matcher.results().map(result -> result.group(1)).collect(Collectors.toUnmodifiableSet());
	}

	private Set<String> entityTables() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

		return scanner.findCandidateComponents("com.buyeoon").stream()
				.map(candidate -> loadClass(candidate.getBeanClassName()))
				.map(entityClass -> entityClass.getAnnotation(Table.class).name())
				.collect(Collectors.toUnmodifiableSet());
	}

	private Class<?> loadClass(String className) {
		try {
			return Class.forName(className);
		} catch (ClassNotFoundException exception) {
			throw new IllegalStateException("Entity class could not be loaded: " + className, exception);
		}
	}
}
