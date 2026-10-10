package com.stelody.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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

  record Profile(String id, String name, String unit, String chzzkId, String xHandle) {}

  static final List<Profile> PROFILES =
      List.of(
          new Profile(
              "808c2423-aff5-5311-930a-ffcb2c7ff69d",
              "아야츠노 유니",
              "Everys",
              "45e71a76e949e16a34764deb962f9d9f",
              "AyatsunoYuni"),
          new Profile(
              "ae936a73-eda5-5c3c-a59d-33ee34d7576d",
              "사키하네 후야",
              "Everys",
              "36ddb9bb4f17593b60f1b63cec86611d",
              "SakihaneHuya"),
          new Profile(
              "87a9538a-dade-571b-b249-ce12099fd04b",
              "시라유키 히나",
              "Universe",
              "b044e3a3b9259246bc92e863e7d3f3b8",
              "Shirayukihina_"),
          new Profile(
              "4360bf14-2f89-536b-bae3-e8a66b2a58b9",
              "네네코 마시로",
              "Universe",
              "4515b179f86b67b4981e16190817c580",
              "NenekoMashiro"),
          new Profile(
              "e2d35b29-639f-5509-a0fb-515c5da88900",
              "아카네 리제",
              "Universe",
              "4325b1d5bbc321fad3042306646e2e50",
              "AkaneLize"),
          new Profile(
              "c115d377-9edf-5b22-a6b6-58f2e589826b",
              "아라하시 타비",
              "Universe",
              "a6c4ddb09cdb160478996007bff35296",
              "ArahashiTabi"),
          new Profile(
              "a75974b0-e1de-50ae-a76e-c5eaf209c3f1",
              "텐코 시부키",
              "Cliché",
              "64d76089fba26b180d9c9e48a32600d9",
              "TenkoShibuki"),
          new Profile(
              "1cbb407e-162d-40fa-8091-08e0a9582faf",
              "아오쿠모 린",
              "Cliché",
              "516937b5f85cbf2249ce31b0ad046b0f",
              "AokumoRin"),
          new Profile(
              "bd27574c-1aea-5517-a014-e854a8eb15b8",
              "하나코 나나",
              "Cliché",
              "4d812b586ff63f8a2946e64fa860bbf5",
              "HanakoNana_"),
          new Profile(
              "52e2098e-5593-4d53-b599-2ed7063a22a0",
              "유즈하 리코",
              "Cliché",
              "8fd39bb8de623317de90654718638b10",
              "YuzuhaRiko"),
          new Profile("15b1cac9-a33a-509e-beed-1935c567659d", "아이리 칸나", "Mystic", null, null));

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
    for (var profile : PROFILES)
      owner.update(
          "INSERT INTO app.member(id,name,search_name,generation,activity_status,version) VALUES(?,?,?,1,?,7)",
          UUID.fromString(profile.id()),
          profile.name(),
          profile.name(),
          profile.unit().equals("Mystic") ? "GRADUATED" : "ACTIVE");
    owner.execute(
        "INSERT INTO app.member(id,name,search_name,activity_status,version) VALUES('00000000-0000-0000-0000-000000000002','아야츠노 유니','아야츠노 유니','ACTIVE',7)");
    var existing =
        owner.queryForList("SELECT id,name,generation,activity_status FROM app.member ORDER BY id");
    assertThat(cfg.target("20").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(cfg.load().migrate().migrationsExecuted).isZero();
    assertThat(
            owner.queryForList(
                "SELECT id,name,generation,activity_status FROM app.member ORDER BY id"))
        .isEqualTo(existing);
    assertThat(
            owner.queryForObject(
                "SELECT version FROM app.member WHERE id='00000000-0000-0000-0000-000000000001'",
                Long.class))
        .isEqualTo(7);
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM app.member WHERE unit_name IS NULL AND chzzk_url IS NULL AND x_url IS NULL",
                Integer.class))
        .isEqualTo(2);
    assertProfiles(owner);
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM app.member WHERE edited_at IS NOT NULL AND version=8",
                Integer.class))
        .isEqualTo(11);
    var runtime =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    runtime.execute(
        "UPDATE app.member SET unit_name='Universe',chzzk_url='https://chzzk.naver.com/member',x_url='https://x.com/member' WHERE id='00000000-0000-0000-0000-000000000001'");
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
                "SELECT generation,unit_name,chzzk_url,x_url,version FROM app.member WHERE id='00000000-0000-0000-0000-000000000001'"))
        .containsEntry("generation", 2)
        .containsEntry("unit_name", "Universe")
        .containsEntry("chzzk_url", "https://chzzk.naver.com/member")
        .containsEntry("x_url", "https://x.com/member")
        .containsEntry("version", 7L);
    assertProfiles(target);
    target.execute(
        "UPDATE app.member SET unit_name=NULL,chzzk_url=NULL,x_url=NULL WHERE id='00000000-0000-0000-0000-000000000001'");
  }

  private void assertProfiles(JdbcTemplate jdbc) {
    for (var profile : PROFILES) {
      var row =
          jdbc.queryForMap(
              "SELECT unit_name,chzzk_url,x_url,version FROM app.member WHERE id=?",
              UUID.fromString(profile.id()));
      assertThat(row)
          .as(profile.name())
          .containsEntry("unit_name", profile.unit())
          .containsEntry(
              "chzzk_url",
              profile.chzzkId() == null ? null : "https://chzzk.naver.com/" + profile.chzzkId())
          .containsEntry(
              "x_url", profile.xHandle() == null ? null : "https://x.com/" + profile.xHandle())
          .containsEntry("version", 8L);
    }
  }
}
