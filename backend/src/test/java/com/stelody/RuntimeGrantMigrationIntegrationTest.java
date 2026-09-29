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
class RuntimeGrantMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void existingV1DatabaseGetsRuntimeAccessWithoutChangingOwnership() throws Exception {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    assertThat(configuration.target("1").load().migrate().migrationsExecuted).isEqualTo(1);

    try (var runtime =
            DriverManager.getConnection(postgres.getJdbcUrl(), "stelody_app", "test-runtime");
        var statement = runtime.createStatement()) {
      assertThatThrownBy(() -> statement.executeQuery("select * from session.spring_session"))
          .isInstanceOfSatisfying(
              SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42501"));
      assertThat(configuration.target("2").load().migrate().migrationsExecuted).isEqualTo(1);
      try (var rows = statement.executeQuery("select * from session.spring_session")) {
        assertThat(rows.next()).isFalse();
      }
      try (var rows =
          statement.executeQuery(
              "select pg_get_userbyid(relowner) from pg_class where oid = 'session.spring_session'::regclass")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("stelody_migrator");
      }
      assertThat(configuration.load().migrate().migrationsExecuted).isZero();
    }
  }
}
