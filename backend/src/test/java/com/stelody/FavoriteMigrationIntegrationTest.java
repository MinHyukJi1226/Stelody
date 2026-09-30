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
class FavoriteMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV5PreservingAccountsCatalogAndSessionsAndGrantsOnlyFavoriteCrud() throws Exception {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    configuration.target("5").load().migrate();
    try (var writer =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
        var statement = writer.createStatement()) {
      statement.execute(
          "INSERT INTO app.app_user(id, google_subject, email) VALUES ('00000000-0000-0000-0000-000000000001', 'migration-test', 'migration@example.invalid')");
      statement.execute(
          "INSERT INTO app.song_entry(id, title, search_title, song_type) VALUES ('00000000-0000-0000-0000-000000000002', 'Existing song', 'existing song', 'COVER')");
      statement.execute(
          """
          INSERT INTO session.spring_session(primary_id, session_id, creation_time, last_access_time,
            max_inactive_interval, expiry_time, principal_name)
          VALUES ('migration-primary', 'migration-session', 1, 1, 1800, 1800001, 'migration-user')
          """);
    }
    assertThat(configuration.target("6").load().migrate().migrationsExecuted).isEqualTo(1);
    try (var runtime =
            DriverManager.getConnection(postgres.getJdbcUrl(), "stelody_app", "test-runtime");
        var statement = runtime.createStatement()) {
      for (String table :
          new String[] {"app.app_user", "app.song_entry", "session.spring_session"}) {
        try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(1);
        }
      }
      statement.execute(
          "INSERT INTO app.favorite(user_id, song_id) VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002')");
      try (var rows =
          statement.executeQuery(
              """
          SELECT has_table_privilege(current_user, 'app.favorite', 'SELECT,INSERT,DELETE'),
            has_table_privilege(current_user, 'app.favorite', 'UPDATE'),
            has_table_privilege(current_user, 'app.favorite', 'TRUNCATE'),
            has_schema_privilege(current_user, 'app', 'CREATE')
          """)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean(1)).isTrue();
        for (int n = 2; n <= 4; n++) assertThat(rows.getBoolean(n)).isFalse();
      }
      assertThat(statement.executeUpdate("DELETE FROM app.favorite")).isEqualTo(1);
    }
    assertThat(configuration.load().migrate().migrationsExecuted).isZero();
  }
}
