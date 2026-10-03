package com.stelody.collection;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class CollectionOperationsMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV14PreservesHistoryAndGrantsOnlyRequestColumnsToWeb() {
    var cfg =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("14").load().migrate();
    var ds =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    var owner = new JdbcTemplate(ds);
    UUID user = UUID.randomUUID(),
        run = UUID.randomUUID(),
        discovery = UUID.randomUUID(),
        generation = UUID.randomUUID();
    owner.update(
        "INSERT INTO app.app_user(id,google_subject,email) VALUES(?,'migration','test@example.invalid')",
        user);
    owner.update(
        "INSERT INTO app.youtube_connection(user_id,generation,status) VALUES(?,?,'DISCONNECTED')",
        user,
        generation);
    owner.update(
        "INSERT INTO app.collection_run(id,logical_slot,status,attempt,started_at,error_code) VALUES(?,date_trunc('hour',now()),'FAILED',2,now(),'HTTP_503')",
        run);
    owner.update(
        "INSERT INTO app.discovery_run(id,mode,status,started_at,error_code) VALUES(?,'NEW','FAILED',now(),'HTTP_503')",
        discovery);
    assertThat(cfg.target("15").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(cfg.load().migrate().migrationsExecuted).isZero();
    var web =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    assertThat(
            web.queryForObject(
                "SELECT attempt FROM app.collection_run WHERE id=?", Integer.class, run))
        .isEqualTo(2);
    assertThat(
            web.queryForObject(
                "SELECT mode FROM app.discovery_run WHERE id=?", String.class, discovery))
        .isEqualTo("NEW");
    assertThat(
            web.queryForObject(
                "SELECT generation FROM app.youtube_connection WHERE user_id=?", UUID.class, user))
        .isEqualTo(generation);
    UUID request = UUID.randomUUID();
    web.update(
        "INSERT INTO app.collection_retry_request(id,kind,run_id,expected_attempt) VALUES(?,'VIDEO',?,2)",
        request,
        run);
    assertThat(
            web.queryForObject(
                "SELECT status FROM app.collection_retry_request WHERE id=?",
                String.class,
                request))
        .isEqualTo("QUEUED");
    assertThatThrownBy(
            () -> web.execute("UPDATE app.collection_retry_request SET status='SUCCEEDED'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                web.update(
                    "INSERT INTO app.collection_retry_request(id,kind,run_id,expected_attempt) VALUES(?,'DISCOVERY',?,1)",
                    UUID.randomUUID(),
                    discovery))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    var collector =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_collector", "test-collector"));
    assertThatThrownBy(() -> collector.queryForList("SELECT * FROM app.collection_retry_request"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    new ResourceDatabasePopulator(new ClassPathResource("collection-operations-grants.sql"))
        .execute(ds);
    collector.update(
        "UPDATE app.collection_retry_request SET status='FAILED',finished_at=now(),error_code='TEST' WHERE id=?",
        request);
    assertThat(
            web.queryForObject(
                "SELECT status FROM app.collection_retry_request WHERE id=?",
                String.class,
                request))
        .isEqualTo("FAILED");
    for (String table : List.of("app_user", "youtube_connection", "catalog_audit"))
      assertThatThrownBy(() -> collector.queryForList("SELECT * FROM app." + table))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
