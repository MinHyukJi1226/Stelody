package com.stelody.collector;

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
class DiscoveryMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV8WithoutCollectorRoleAndPreservesExistingData() {
    var config =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    config.target("8").load().migrate();
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    admin.execute("DROP OWNED BY stelody_collector; DROP ROLE stelody_collector");
    var writer =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    writer.execute(
        "INSERT INTO app.app_user(id,google_subject,email) VALUES('00000000-0000-0000-0000-000000000001','test','test@example.invalid')");
    assertThat(config.target("9").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(config.load().migrate().migrationsExecuted).isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.app_user", Long.class)).isEqualTo(1);
    var runtime =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.review_item", Long.class)).isZero();
    assertThatThrownBy(
            () -> runtime.execute("UPDATE app.discovery_channel_state SET page_token=NULL"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
