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
	private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE ([a-z_]+) ");

	@Test
	void initialMigrationMatchesCanonicalSchema() throws IOException {
		assertThat(Files.readString(INITIAL_MIGRATION, StandardCharsets.UTF_8))
				.isEqualTo(Files.readString(SCHEMA_SOURCE, StandardCharsets.UTF_8));
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
