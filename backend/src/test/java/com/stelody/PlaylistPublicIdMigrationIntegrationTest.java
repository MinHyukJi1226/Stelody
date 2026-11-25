package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class PlaylistPublicIdMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void backfillsExistingListsAndIssuesUniqueAddressesForRuntimeInserts() throws Exception {
    var configuration =
        Flyway.configure()
            .locations(com.stelody.LegacyMigrations.location())
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    configuration.target("34").load().migrate();
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
        var sql = connection.createStatement()) {
      sql.execute(
          "INSERT INTO app.app_user(id,google_subject,email) VALUES ('00000000-0000-0000-0000-000000000001','public-id-test','test@example.invalid')");
      sql.execute(
          "INSERT INTO app.playlist(id,user_id,name,created_at,updated_at,version) VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','existing',now(),now(),7)");
    }
    configuration.target("35").load().migrate();
    try (var connection =
            DriverManager.getConnection(postgres.getJdbcUrl(), "stelody_app", "test-runtime");
        var sql = connection.createStatement()) {
      String existing;
      try (var rows = sql.executeQuery("SELECT public_id,name,version FROM app.playlist")) {
        assertThat(rows.next()).isTrue();
        existing = rows.getString(1);
        assertThat(existing).matches("[A-Za-z0-9]{10}");
        assertThat(rows.getString(2)).isEqualTo("existing");
        assertThat(rows.getLong(3)).isEqualTo(7);
      }
      sql.execute(
          "INSERT INTO app.playlist(id,user_id,name,created_at,updated_at) SELECT gen_random_uuid(),'00000000-0000-0000-0000-000000000001','new',now(),now() FROM generate_series(1,200)");
      try (var rows =
          sql.executeQuery(
              "SELECT count(*),count(DISTINCT public_id),bool_and(public_id ~ '^[A-Za-z0-9]{10}$') FROM app.playlist")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(201);
        assertThat(rows.getInt(2)).isEqualTo(201);
        assertThat(rows.getBoolean(3)).isTrue();
      }
      assertThatThrownBy(
              () ->
                  sql.execute(
                      "UPDATE app.playlist SET public_id='" + existing + "' WHERE name='new'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));
      assertThatThrownBy(
              () ->
                  sql.execute("UPDATE app.playlist SET public_id='invalid!' WHERE name='existing'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
      try (var rows =
          sql.executeQuery("SELECT public_id FROM app.playlist WHERE name='existing'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(existing);
      }
    }
  }
}
