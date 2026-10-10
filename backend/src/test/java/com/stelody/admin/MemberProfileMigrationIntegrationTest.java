package com.stelody.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class MemberProfileMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesExistingMemberAndRestoresNewProfileColumnsWithRuntimePermissions() throws Exception {
    var cfg =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("19").load().migrate();
    var owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    owner.execute(
        "INSERT INTO app.member(id,name,search_name,generation,activity_status,version) VALUES('00000000-0000-0000-0000-000000000001','keep','keep',2,'GRADUATED',7)");
    assertThat(cfg.target("20").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(cfg.load().migrate().migrationsExecuted).isZero();
    assertThat(owner.queryForObject("SELECT version FROM app.member", Long.class)).isEqualTo(7);
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM app.member WHERE unit_name IS NULL AND chzzk_url IS NULL AND x_url IS NULL",
                Integer.class))
        .isEqualTo(1);
    var runtime =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    runtime.execute(
        "UPDATE app.member SET unit_name='Universe',chzzk_url='https://chzzk.naver.com/member',x_url='https://x.com/member'");
    var dump =
        postgres.execInContainer(
            "pg_dump",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "--format=custom",
            "--schema=app",
            "--schema=session",
            "--extension=pg_trgm",
            "--no-owner",
            "--file=/tmp/member-profiles.dump");
    assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();
    assertThat(
            postgres
                .execInContainer(
                    "createdb",
                    "-U",
                    postgres.getUsername(),
                    "-O",
                    "stelody_migrator",
                    "member_profiles_restore")
                .getExitCode())
        .isZero();
    var restored =
        postgres.execInContainer(
            "pg_restore",
            "-U",
            "stelody_migrator",
            "--dbname=member_profiles_restore",
            "--no-owner",
            "--exit-on-error",
            "--single-transaction",
            "/tmp/member-profiles.dump");
    assertThat(restored.getExitCode()).as(restored.getStderr()).isZero();
    var target =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:postgresql://"
                    + postgres.getHost()
                    + ":"
                    + postgres.getMappedPort(5432)
                    + "/member_profiles_restore",
                "stelody_app",
                "test-runtime"));
    assertThat(
            target.queryForMap(
                "SELECT generation,unit_name,chzzk_url,x_url,version FROM app.member"))
        .containsEntry("generation", 2)
        .containsEntry("unit_name", "Universe")
        .containsEntry("chzzk_url", "https://chzzk.naver.com/member")
        .containsEntry("x_url", "https://x.com/member")
        .containsEntry("version", 7L);
    target.execute("UPDATE app.member SET unit_name=NULL,chzzk_url=NULL,x_url=NULL");
  }
}
