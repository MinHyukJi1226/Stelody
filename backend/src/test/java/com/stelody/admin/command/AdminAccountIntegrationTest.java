package com.stelody.admin.command;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class AdminAccountIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  static final UUID USER = new UUID(0, 1), SUSPENDED = new UUID(0, 2);
  JdbcTemplate owner, operator;
  AdminAccountCommand command;

  @BeforeEach
  void setup() throws Exception {
    var cfg =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("11").load().migrate();
    owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    owner.execute("TRUNCATE app.app_user,app.catalog_audit CASCADE");
    owner.update(
        "INSERT INTO app.app_user(id,google_subject,email,status) VALUES(?,'one','one@example.invalid','ACTIVE'),(?,'two','two@example.invalid','SUSPENDED')",
        USER,
        SUSPENDED);
    owner.execute(
        new String(
            getClass().getResourceAsStream("/admin-operator-grants.sql").readAllBytes(),
            StandardCharsets.UTF_8));
    var ds =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_operator", "test-operator");
    operator = new JdbcTemplate(ds);
    command = new AdminAccountCommand(ds);
  }

  @Test
  void assignmentRevocationAndNoOpAreAuditedWithDatabaseActor() {
    assertThat(command.change(USER, "ADMIN", "verified operator")).isTrue();
    assertThat(command.change(USER, "ADMIN", "repeat")).isFalse();
    assertThat(command.change(USER, "USER", "revoke")).isTrue();
    assertThat(owner.queryForObject("SELECT role FROM app.app_user WHERE id=?", String.class, USER))
        .isEqualTo("USER");
    assertThat(owner.queryForObject("SELECT count(*) FROM app.catalog_audit", Long.class))
        .isEqualTo(2);
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE database_actor='stelody_operator' AND actor_id IS NULL",
                Long.class))
        .isEqualTo(2);
  }

  @Test
  void suspendedMissingAndInvalidRolesCannotBeGranted() {
    assertThatThrownBy(() -> command.change(SUSPENDED, "ADMIN", "fixture"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> command.change(UUID.randomUUID(), "ADMIN", "fixture"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> command.change(USER, "OWNER", "fixture"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> command.change(USER, "ADMIN", " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(owner.queryForObject("SELECT count(*) FROM app.catalog_audit", Long.class)).isZero();
  }

  @Test
  void auditFailureRollsBackRoleChange() {
    owner.execute("REVOKE INSERT ON app.catalog_audit FROM stelody_operator");
    assertThatThrownBy(() -> command.change(USER, "ADMIN", "fixture"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(owner.queryForObject("SELECT role FROM app.app_user WHERE id=?", String.class, USER))
        .isEqualTo("USER");
  }

  @Test
  void operatorCannotReadEmailOrWriteStatusAndWebCannotCreatePrivilegedUsers() {
    assertThatThrownBy(() -> operator.execute("SELECT email FROM app.app_user"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> operator.execute("UPDATE app.app_user SET status='SUSPENDED'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    var runtime =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    assertThatThrownBy(
            () ->
                runtime.update(
                    "INSERT INTO app.app_user(id,google_subject,email,role) VALUES(?,'injected','fixture@example.invalid','ADMIN')",
                    UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> runtime.execute("UPDATE app.app_user SET role='ADMIN'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
