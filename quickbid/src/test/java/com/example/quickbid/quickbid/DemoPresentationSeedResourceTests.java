package com.example.quickbid.quickbid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class DemoPresentationSeedResourceTests {
	private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

	@Test
	void migrationsV1ThroughV17AndDemoSeedResourcesArePresent() throws Exception {
		for (int version : IntStream.rangeClosed(1, 17).toArray()) {
			try (Stream<Path> files = Files.list(MIGRATIONS)) {
				assertEquals(1, files
						.filter(path -> path.getFileName().toString().startsWith("V" + version + "__"))
						.count(), "Migration V" + version + " must exist exactly once");
			}
		}

		String v16 = Files.readString(MIGRATIONS.resolve("V16__demo_short_auction_dates.sql"));
		String v17 = Files.readString(MIGRATIONS.resolve("V17__demo_presentation_multilot_seed.sql"));
		assertTrue(v16.contains("DROP CONSTRAINT IF EXISTS \"chkFecha\""));
		assertTrue(v16.contains("fecha >= CURRENT_DATE"));
		assertTrue(v17.contains("6011"));
		assertTrue(v17.contains("comprador2@quickbid.demo"));
		assertTrue(v17.contains("categoria@quickbid.demo"));
		assertTrue(v17.contains("9011") && v17.contains("9012") && v17.contains("9013"));
		assertTrue(v17.contains("16111") && v17.contains("16112") && v17.contains("16113"));
		assertTrue(v17.contains("POL-DEMO-8011"));
		assertTrue(v17.contains("content_bytes"));

		assertTrue(Files.exists(Path.of("docs/demo_seed_validation.sql")));
		assertTrue(Files.exists(Path.of("docs/10_demo_presentacion_full_flow.http")));
		assertTrue(Files.exists(Path.of("docs/demo_pgadmin_queries.sql")));
	}
}
