package com.stelody.export;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class ExportMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV13PreservesPersonalListAndRestrictsTokensToWebRole() {
    var cfg =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("13").load().migrate();
    var owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    UUID user = UUID.randomUUID(), playlist = UUID.randomUUID();
    owner.update(
        "INSERT INTO app.app_user(id,google_subject,email) VALUES(?,'migration-user','migration@example.invalid')",
        user);
    owner.update(
        "INSERT INTO app.playlist(id,user_id,name,created_at,updated_at) VALUES(?,?,'kept',now(),now())",
        playlist,
        user);
    assertThat(cfg.target("14").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(cfg.load().migrate().migrationsExecuted).isZero();
    var web =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    assertThat(
            web.queryForObject("SELECT name FROM app.playlist WHERE id=?", String.class, playlist))
        .isEqualTo("kept");
    web.update(
        "INSERT INTO app.youtube_connection(user_id,generation,status) VALUES(?,?,'DISCONNECTED')",
        user,
        UUID.randomUUID());
    for (String role : List.of("stelody_collector", "stelody_operator")) {
      var source =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  postgres.getJdbcUrl(),
                  role,
                  role.equals("stelody_collector") ? "test-collector" : "test-operator"));
      for (String table :
          List.of(
              "youtube_connection",
              "youtube_authorization",
              "youtube_export",
              "youtube_export_item"))
        assertThatThrownBy(() -> source.queryForList("SELECT * FROM app." + table))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    owner.update("DELETE FROM app.app_user WHERE id=?", user);
    assertThat(web.queryForObject("SELECT count(*) FROM app.youtube_connection", Long.class))
        .isZero();
  }
}
