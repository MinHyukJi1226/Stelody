package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class CatalogMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV3AndUsesExistingExtensionSchemaWithoutMovingIt() throws Exception {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    configuration.target("3").load().migrate();
    try (var admin =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = admin.createStatement()) {
      statement.execute("CREATE EXTENSION pg_trgm WITH SCHEMA public");
    }
    assertThat(configuration.target("5").load().migrate().migrationsExecuted).isEqualTo(2);
    try (var runtime =
            DriverManager.getConnection(postgres.getJdbcUrl(), "stelody_app", "test-runtime");
        var statement = runtime.createStatement()) {
      try (var rows =
          statement.executeQuery(
              "SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pg_trgm'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("public");
      }
      try (var rows =
          statement.executeQuery(
              "SELECT count(*) FROM pg_indexes WHERE schemaname = 'app' AND indexname LIKE '%_trgm_idx'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(8);
      }
      try (var rows =
          statement.executeQuery(
              "SELECT count(*) FROM app.song_entry WHERE search_title LIKE '%sample%'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isZero();
      }
    }
    assertThat(configuration.load().migrate().migrationsExecuted).isZero();
  }
}
