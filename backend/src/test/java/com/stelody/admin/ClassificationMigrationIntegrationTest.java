package com.stelody.admin;

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
class ClassificationMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV15WithCustomRuntimeRoleWithoutCollectorAndPreservesManualData() {
    var administrator =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    administrator.execute("CREATE ROLE classification_web LOGIN PASSWORD 'test-classification'");
    var cfg =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "classification_web"));
    cfg.target("15").load().migrate();
    var owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    UUID song = UUID.randomUUID();
    owner.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility,is_special_event,special_event_label,version) VALUES(?,'manual','manual','COVER','HIDDEN',true,'keep badge',7)",
        song);
    administrator.execute("DROP OWNED BY stelody_collector");
    administrator.execute("DROP ROLE stelody_collector");
    assertThat(cfg.target("17").load().migrate().migrationsExecuted).isEqualTo(2);
    assertThat(cfg.load().migrate().migrationsExecuted).isZero();
    var web =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "classification_web", "test-classification"));
    assertThat(
            web.queryForObject("SELECT version FROM app.song_entry WHERE id=?", Long.class, song))
        .isEqualTo(7);
    assertThat(
            web.queryForObject(
                "SELECT special_event_label FROM app.song_entry WHERE id=?", String.class, song))
        .isEqualTo("keep badge");
    assertThat(web.queryForObject("SELECT count(*) FROM app.special_event_review", Integer.class))
        .isZero();
    assertThat(web.queryForObject("SELECT version FROM app.collection_rule", Long.class)).isZero();
    web.execute("UPDATE app.collection_rule SET version=1");
    assertThat(
            web.queryForObject(
                "SELECT has_column_privilege(current_user,'app.special_event_review','song_id','UPDATE')",
                Boolean.class))
        .isFalse();
    assertThat(
            web.queryForObject(
                "SELECT has_table_privilege(current_user,'app.special_event_review','DELETE')",
                Boolean.class))
        .isFalse();
    assertThat(
            web.queryForObject(
                "SELECT has_column_privilege(current_user,'app.video','source_title','UPDATE')",
                Boolean.class))
        .isFalse();
    assertThat(
            web.queryForObject(
                "SELECT has_table_privilege('stelody_app','app.collection_rule','SELECT')",
                Boolean.class))
        .isFalse();
  }
}
