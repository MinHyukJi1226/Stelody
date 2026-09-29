package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class InfrastructureIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "stelody_app");
    registry.add("spring.datasource.password", () -> "test-runtime");
    registry.add("spring.flyway.user", () -> "stelody_migrator");
    registry.add("spring.flyway.password", () -> "test-migrator");
  }

  @Autowired MockMvc mvc;
  @Autowired SessionRepository<?> sessions;
  @Autowired JdbcTemplate jdbc;

  @Test
  void runtimeRoleHasOnlyRequiredPrivileges() {
    assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("stelody_app");
    assertThat(
            jdbc.queryForObject(
                "select rolsuper from pg_roles where rolname = current_user", Boolean.class))
        .isFalse();
    for (String schema : new String[] {"app", "session"}) {
      assertThat(
              jdbc.queryForObject(
                  "select pg_get_userbyid(nspowner) from pg_namespace where nspname = ?",
                  String.class,
                  schema))
          .isEqualTo("stelody_migrator");
      assertThat(
              jdbc.queryForObject(
                  "select has_schema_privilege(current_user, ?, 'USAGE')", Boolean.class, schema))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select has_schema_privilege(current_user, ?, 'CREATE')", Boolean.class, schema))
          .isFalse();
    }
    assertThatThrownBy(() -> jdbc.execute("CREATE TABLE app.forbidden (id int)"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.execute("DROP TABLE session.spring_session_attributes"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM app.flyway_schema_history"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void healthIsPublicWithoutDatabaseDetails() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void migratedSessionTablesSupportSaveReadAndDelete() {
    verifySessionPersistence(sessions);
  }

  private <S extends Session> void verifySessionPersistence(SessionRepository<S> sessions) {
    var session = sessions.createSession();
    session.setAttribute("testAttribute", "persisted");
    sessions.save(session);
    Session restored = sessions.findById(session.getId());
    assertThat(restored).isNotNull();
    assertThat((String) restored.getAttribute("testAttribute")).isEqualTo("persisted");
    assertThat(restored.getMaxInactiveInterval().toMinutes()).isEqualTo(30);
    session.setAttribute("testAttribute", "updated");
    sessions.save(session);
    assertThat((String) sessions.findById(session.getId()).getAttribute("testAttribute"))
        .isEqualTo("updated");
    sessions.deleteById(session.getId());
    assertThat(sessions.findById(session.getId())).isNull();
    var expired = sessions.createSession();
    expired.setAttribute("testAttribute", "expired");
    expired.setLastAccessedTime(Instant.now().minusSeconds(3600));
    sessions.save(expired);
    ((JdbcIndexedSessionRepository) this.sessions).cleanUpExpiredSessions();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from session.spring_session where session_id = ?",
                Integer.class,
                expired.getId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from session.spring_session_attributes", Integer.class))
        .isZero();
  }
}
