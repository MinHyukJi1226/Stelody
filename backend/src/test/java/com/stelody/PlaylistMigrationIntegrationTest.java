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
class PlaylistMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV6PreservingExistingDataAndPermitsAtomicPositionSwapsWithLimitedRuntimePrivileges()
      throws Exception {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    configuration.target("6").load().migrate();
    try (var writer =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
        var statement = writer.createStatement()) {
      statement.execute(
          "INSERT INTO app.app_user(id, google_subject, email) VALUES ('00000000-0000-0000-0000-000000000001', 'migration-test', 'migration@example.invalid')");
      statement.execute(
          """
          INSERT INTO app.song_entry(id, title, search_title, song_type)
          VALUES ('00000000-0000-0000-0000-000000000002', 'Existing song', 'existing song', 'COVER'),
            ('00000000-0000-0000-0000-000000000003', 'Other song', 'other song', 'COVER')
          """);
      statement.execute(
          "INSERT INTO app.favorite(user_id, song_id) VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002')");
      statement.execute(
          """
          INSERT INTO session.spring_session(primary_id, session_id, creation_time, last_access_time,
            max_inactive_interval, expiry_time, principal_name)
          VALUES ('migration-primary', 'migration-session', 1, 1, 1800, 1800001, 'migration-user')
          """);
    }
    assertThat(configuration.target("7").load().migrate().migrationsExecuted).isEqualTo(1);
    try (var runtime =
            DriverManager.getConnection(postgres.getJdbcUrl(), "stelody_app", "test-runtime");
        var statement = runtime.createStatement()) {
      for (var entry :
          Map.of(
                  "app.app_user",
                  1,
                  "app.song_entry",
                  2,
                  "app.favorite",
                  1,
                  "session.spring_session",
                  1)
              .entrySet()) {
        try (var rows = statement.executeQuery("SELECT count(*) FROM " + entry.getKey())) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(entry.getValue());
        }
      }
      statement.execute(
          """
          INSERT INTO app.playlist(id, user_id, name, created_at, updated_at)
          VALUES ('00000000-0000-0000-0000-000000000064', '00000000-0000-0000-0000-000000000001', 'Migration list', now(), now())
          """);
      statement.execute(
          """
          INSERT INTO app.playlist_item(id, playlist_id, song_id, position, added_at)
          VALUES ('00000000-0000-0000-0000-000000000065', '00000000-0000-0000-0000-000000000064', '00000000-0000-0000-0000-000000000002', 0, now()),
            ('00000000-0000-0000-0000-000000000066', '00000000-0000-0000-0000-000000000064', '00000000-0000-0000-0000-000000000003', 1, now())
          """);
      runtime.setAutoCommit(false);
      statement.executeUpdate("UPDATE app.playlist_item SET position = 1 - position");
      runtime.commit();
      runtime.setAutoCommit(true);
      try (var rows =
          statement.executeQuery("SELECT song_id::text FROM app.playlist_item ORDER BY position")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).endsWith("0003");
      }
      runtime.setAutoCommit(false);
      statement.executeUpdate("UPDATE app.playlist_item SET position = 0");
      assertThatThrownBy(runtime::commit)
          .isInstanceOfSatisfying(
              SQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo("23505"));
      runtime.rollback();
      runtime.setAutoCommit(true);
      for (String table : new String[] {"app.playlist", "app.playlist_item"}) {
        for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE"}) {
          try (var rows =
              statement.executeQuery(
                  "SELECT has_table_privilege(current_user, '"
                      + table
                      + "', '"
                      + privilege
                      + "')")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBoolean(1)).isTrue();
          }
        }
        try (var rows =
            statement.executeQuery(
                "SELECT has_table_privilege(current_user, '" + table + "', 'TRUNCATE')")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getBoolean(1)).isFalse();
        }
      }
      assertThatThrownBy(() -> statement.execute("UPDATE app.song_entry SET title = 'Forbidden'"))
          .isInstanceOfSatisfying(
              SQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(() -> statement.execute("CREATE TABLE app.forbidden_table(id int)"))
          .isInstanceOfSatisfying(
              SQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo("42501"));
      assertThat(statement.executeUpdate("UPDATE app.playlist SET name = 'Changed'")).isEqualTo(1);
      assertThat(statement.executeUpdate("DELETE FROM app.playlist")).isEqualTo(1);
      try (var rows = statement.executeQuery("SELECT count(*) FROM app.playlist_item")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isZero();
      }
    }
    assertThat(configuration.load().migrate().migrationsExecuted).isZero();
  }
}
