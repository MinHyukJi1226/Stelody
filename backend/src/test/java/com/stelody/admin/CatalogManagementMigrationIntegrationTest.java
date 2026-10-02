package com.stelody.admin;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class CatalogManagementMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV10WithoutOperatorLoginAndPreservesCatalogAndReview() {
    var cfg =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("10").load().migrate();
    var owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    owner.execute(
        "INSERT INTO app.member(id,name,search_name,activity_status,version) VALUES('00000000-0000-0000-0000-000000000001','keep','keep','GRADUATED',7)");
    owner.execute(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type) VALUES('00000000-0000-0000-0000-000000000002','UCaaaaaaaaaaaaaaaaaaaaaa','keep channel','GROUP')");
    owner.execute(
        "INSERT INTO app.review_item(id,youtube_id,channel_id,source_observed_at,availability,disposition,suggested_type,rule_version,decision_reason,first_seen_at,review_status,review_note) VALUES('00000000-0000-0000-0000-000000000003','abcdefghijk','00000000-0000-0000-0000-000000000002',now(),'PUBLIC','EXCLUDED','UNKNOWN','test','exclude',now(),'IGNORED','keep note')");
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    admin.execute("DROP OWNED BY stelody_operator; DROP ROLE stelody_operator");
    assertThat(cfg.target("12").load().migrate().migrationsExecuted).isEqualTo(2);
    assertThat(cfg.load().migrate().migrationsExecuted).isZero();
    assertThat(owner.queryForObject("SELECT version FROM app.member", Long.class)).isEqualTo(7);
    assertThat(owner.queryForObject("SELECT review_note FROM app.review_item", String.class))
        .isEqualTo("keep note");
    var runtime =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    assertThat(
            runtime.queryForObject(
                "SELECT has_column_privilege(current_user,'app.app_user','role','UPDATE')",
                Boolean.class))
        .isFalse();
    assertThat(
            runtime.queryForObject(
                "SELECT has_column_privilege(current_user,'app.app_user','email','UPDATE')",
                Boolean.class))
        .isTrue();
    assertThat(
            runtime.queryForObject(
                "SELECT has_column_privilege(current_user,'app.video','source_title','UPDATE')",
                Boolean.class))
        .isFalse();
    assertThatThrownBy(
            () -> runtime.execute("UPDATE app.member SET birthday_month=2,birthday_day=NULL"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> runtime.execute("UPDATE app.review_item SET review_status='REGISTERED'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}
