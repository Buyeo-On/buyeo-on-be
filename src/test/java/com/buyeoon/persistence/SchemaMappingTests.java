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
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

class SchemaMappingTests {

	private static final Path SCHEMA_SOURCE = Path.of("docs/raw/db-schema.sql");
	private static final Path INITIAL_MIGRATION =
			Path.of("src/main/resources/db/migration/V1__initial_schema.sql");
	private static final Path PLACE_EXTERNAL_ID_MIGRATION =
			Path.of("src/main/resources/db/migration/V2__add_place_external_id.sql");
	private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE ([a-z_]+) ");

	@Test
	void canonicalSchemaMatchesMigrationChain() throws IOException {
		String baseline = Files.readString(INITIAL_MIGRATION, StandardCharsets.UTF_8);
		String canonicalSchema = Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8);
		String placeSourceColumns = String.join(
				"\n",
				"    source_name text, -- 관광데이터 제공처",
				"    source_url text -- 관광데이터 원문 URL",
				"");
		String placeExternalIdentityColumns = String.join(
				"\n",
				"    source_name text, -- 관광데이터 제공처",
				"    external_id text, -- 제공처가 부여한 장소 식별자",
				"    source_url text, -- 관광데이터 원문 URL",
				"    CHECK (external_id IS NULL OR source_name IS NOT NULL)",
				"");
		String placeLocationIndex =
				"CREATE INDEX places_location_gix ON places USING GIST (location);";
		String placeIndexes = String.join(
				"\n",
				"CREATE INDEX places_location_gix ON places USING GIST (location);",
				"CREATE UNIQUE INDEX places_source_external_id_uq",
				"    ON places (source_name, external_id)",
				"    WHERE external_id IS NOT NULL;");

		assertThat(baseline).contains(placeSourceColumns).contains(placeLocationIndex);
		assertThat(baseline
					.replace(placeSourceColumns, placeExternalIdentityColumns)
					.replace(placeLocationIndex, placeIndexes))
				.isEqualTo(canonicalSchema);
	}

	@Test
	void placeExternalIdentityIsDefinedInSchemaAndMigration() throws IOException {
		String canonicalSchema = Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8);
		String migration = Files.readString(PLACE_EXTERNAL_ID_MIGRATION, StandardCharsets.UTF_8);

		assertThat(canonicalSchema)
				.contains("external_id text")
				.contains("CHECK (external_id IS NULL OR source_name IS NOT NULL)")
				.contains("CREATE UNIQUE INDEX places_source_external_id_uq")
				.contains("ON places (source_name, external_id)")
				.contains("WHERE external_id IS NOT NULL");
		assertThat(migration)
				.contains("ADD COLUMN external_id text")
				.contains("CHECK (external_id IS NULL OR source_name IS NOT NULL)")
				.contains("CREATE UNIQUE INDEX places_source_external_id_uq")
				.contains("ON places (source_name, external_id)")
				.contains("WHERE external_id IS NOT NULL");
	}

	@Test
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
		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
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
