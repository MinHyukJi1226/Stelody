package com.stelody.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class AbyssMemberMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  record NewMember(
      String id,
      String name,
      String youtube,
      String chzzk,
      String x,
      String debut,
      int month,
      int day) {}

  record Dates(String id, String debut, int month, int day) {}

  static final List<NewMember> NEW_MEMBERS =
      List.of(
          new NewMember(
              "e45fafbd-eda4-5d6c-b67b-b41999b2ad03",
              "시즈키 토아",
              "UClcSEuDF_g_6g4snw_nAavw",
              "2299fad4bebb504b0978e1ec08b2f7d3",
              "ShizukiToa_",
              "2026-10-17",
              11,
              22),
          new NewMember(
              "b715a5e0-b10f-5a79-8405-9703e0543716",
              "코가네 랑",
              "UCQ6LlsI7Bbj2yhSqLMI1gGA",
              "c657aa3592260a2b59669a72ec151936",
              "CoganeRang",
              "2026-10-17",
              8,
              22),
          new NewMember(
              "4fdb54c3-bfd5-5ef8-9b04-2e016b33d475",
              "하나요 히요리",
              "UCHAr9pWGS30akL4MQCQT4uQ",
              "49d2c3cf8f0b2843d934ec15e82bb54c",
              "HanayoHiyori",
              "2026-10-18",
              3,
              31),
          new NewMember(
              "6ac3f9a8-90d3-5f90-91fb-37ccf645a8b9",
              "토키노 메리",
              "UCGSt1yNG5l-jpYRLpJ5oNtA",
              "792acf9ca28b42918710327e14b06b68",
              "TokinoMary",
              "2026-10-18",
              6,
              13));
  static final List<Dates> EXISTING_DATES =
      List.of(
          new Dates("808c2423-aff5-5311-930a-ffcb2c7ff69d", "2022-01-08", 5, 21),
          new Dates("ae936a73-eda5-5c3c-a59d-33ee34d7576d", "2025-09-20", 7, 7),
          new Dates("87a9538a-dade-571b-b249-ce12099fd04b", "2023-06-10", 1, 5),
          new Dates("4360bf14-2f89-536b-bae3-e8a66b2a58b9", "2023-06-10", 2, 22),
          new Dates("e2d35b29-639f-5509-a0fb-515c5da88900", "2023-06-11", 10, 1),
          new Dates("c115d377-9edf-5b22-a6b6-58f2e589826b", "2023-06-11", 9, 7),
          new Dates("a75974b0-e1de-50ae-a76e-c5eaf209c3f1", "2024-05-18", 3, 21),
          new Dates("1cbb407e-162d-40fa-8091-08e0a9582faf", "2024-05-18", 5, 3),
          new Dates("bd27574c-1aea-5517-a014-e854a8eb15b8", "2024-05-19", 8, 7),
          new Dates("52e2098e-5593-4d53-b599-2ed7063a22a0", "2024-05-19", 4, 13),
          new Dates("15b1cac9-a33a-509e-beed-1935c567659d", "2023-01-07", 12, 2));

  @Test
  void addsOfficialChannelsAndDatesWithoutChangingExistingProfileOrRelationships() {
    var cfg =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("20").load().migrate();
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    for (var profile : MemberProfileMigrationIntegrationTest.PROFILES)
      jdbc.update(
          "INSERT INTO app.member(id,name,search_name,generation,activity_status,unit_name,profile_image_url,version) VALUES(?,?,?,1,?,?,?,7)",
          UUID.fromString(profile.id()),
          profile.name(),
          profile.name(),
          profile.unit().equals("Mystic") ? "GRADUATED" : "ACTIVE",
          profile.unit(),
          "https://example.com/keep.png");
    jdbc.execute(
        "INSERT INTO app.member(id,name,search_name,activity_status,version) VALUES('00000000-0000-0000-0000-000000000001','keep','keep','ACTIVE',3)");
    var firstId = UUID.fromString(EXISTING_DATES.getFirst().id());
    jdbc.update(
        "INSERT INTO app.member_alias(member_id,alias,search_alias) VALUES(?,'keep alias','keep alias')",
        firstId);
    jdbc.execute(
        "INSERT INTO app.song_entry(id,title,search_title,song_type) VALUES('00000000-0000-0000-0000-000000000003','keep song','keep song','COVER')");
    jdbc.update(
        "INSERT INTO app.song_member(song_id,member_id,position) VALUES('00000000-0000-0000-0000-000000000003',?,0)",
        firstId);
    var aliases = jdbc.queryForList("SELECT * FROM app.member_alias");
    var relationships = jdbc.queryForList("SELECT * FROM app.song_member");
    var preserved =
        jdbc.queryForList(
            "SELECT id,name,search_name,generation,activity_status,unit_name,profile_image_url FROM app.member ORDER BY id");
    jdbc.execute(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES('00000000-0000-0000-0000-000000000002','UCaaaaaaaaaaaaaaaaaaaaaa','keep','GROUP',false)");
    assertThat(cfg.target("21").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(cfg.target("21").load().migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT id,name,search_name,generation,activity_status,unit_name,profile_image_url FROM app.member WHERE generation IS DISTINCT FROM 4 ORDER BY id"))
        .isEqualTo(preserved);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM app.member", Integer.class)).isEqualTo(16);
    for (var d : EXISTING_DATES) {
      var row =
          jdbc.queryForMap(
              "SELECT debut_date,birthday_month,birthday_day,version FROM app.member WHERE id=?",
              UUID.fromString(d.id()));
      assertThat(row)
          .containsEntry("debut_date", java.sql.Date.valueOf(d.debut()))
          .containsEntry("birthday_month", d.month())
          .containsEntry("birthday_day", d.day())
          .containsEntry("version", 8L);
    }
    var runtime =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    for (var m : NEW_MEMBERS) {
      var row =
          runtime.queryForMap(
              "SELECT name,search_name,generation,activity_status,unit_name,chzzk_url,x_url,profile_image_url,debut_date,birthday_month,birthday_day FROM app.member WHERE id=?",
              UUID.fromString(m.id()));
      assertThat(row)
          .containsEntry("name", m.name())
          .containsEntry("search_name", m.name())
          .containsEntry("generation", 4)
          .containsEntry("activity_status", "ACTIVE")
          .containsEntry("unit_name", "ABYSS")
          .containsEntry("chzzk_url", "https://chzzk.naver.com/" + m.chzzk())
          .containsEntry("x_url", "https://x.com/" + m.x())
          .containsEntry("profile_image_url", null)
          .containsEntry("debut_date", java.sql.Date.valueOf(m.debut()))
          .containsEntry("birthday_month", m.month())
          .containsEntry("birthday_day", m.day());
      assertThat(
              runtime.queryForMap(
                  "SELECT member_id,channel_type,collection_enabled FROM app.channel WHERE youtube_id=?",
                  m.youtube()))
          .containsEntry("member_id", UUID.fromString(m.id()))
          .containsEntry("channel_type", "MEMBER")
          .containsEntry("collection_enabled", true);
    }
    assertThat(jdbc.queryForObject("SELECT version FROM app.member WHERE name='keep'", Long.class))
        .isEqualTo(3L);
    assertThat(
            jdbc.queryForObject(
                "SELECT collection_enabled FROM app.channel WHERE name='keep'", Boolean.class))
        .isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM app.channel", Integer.class)).isEqualTo(5);
    assertThat(jdbc.queryForList("SELECT * FROM app.member_alias")).isEqualTo(aliases);
    assertThat(jdbc.queryForList("SELECT * FROM app.song_member")).isEqualTo(relationships);
    assertThat(cfg.target("22").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("808c2423-aff5-5311-930a-ffcb2c7ff69d")))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("ae936a73-eda5-5c3c-a59d-33ee34d7576d")))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("4360bf14-2f89-536b-bae3-e8a66b2a58b9")))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("87a9538a-dade-571b-b249-ce12099fd04b")))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("e2d35b29-639f-5509-a0fb-515c5da88900")))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("c115d377-9edf-5b22-a6b6-58f2e589826b")))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("a75974b0-e1de-50ae-a76e-c5eaf209c3f1")))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("1cbb407e-162d-40fa-8091-08e0a9582faf")))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("bd27574c-1aea-5517-a014-e854a8eb15b8")))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("52e2098e-5593-4d53-b599-2ed7063a22a0")))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("e45fafbd-eda4-5d6c-b67b-b41999b2ad03")))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("b715a5e0-b10f-5a79-8405-9703e0543716")))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("4fdb54c3-bfd5-5ef8-9b04-2e016b33d475")))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_order FROM app.member WHERE id=?",
                Integer.class,
                UUID.fromString("6ac3f9a8-90d3-5f90-91fb-37ccf645a8b9")))
        .isEqualTo(4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"member", "channel"})
  void conflictingManualRegistrationRollsBackTheWholeMigration(String conflict) throws Exception {
    var database = "abyss_conflict_" + conflict;
    var created =
        postgres.execInContainer(
            "createdb", "-U", postgres.getUsername(), "-O", "stelody_migrator", database);
    assertThat(created.getExitCode()).as(created.getStderr()).isZero();
    var url =
        "jdbc:postgresql://"
            + postgres.getHost()
            + ":"
            + postgres.getMappedPort(5432)
            + "/"
            + database;
    var cfg =
        Flyway.configure()
            .dataSource(url, "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("20").load().migrate();
    var jdbc =
        new JdbcTemplate(new DriverManagerDataSource(url, "stelody_migrator", "test-migrator"));
    var existing = UUID.fromString(EXISTING_DATES.getFirst().id());
    jdbc.update(
        "INSERT INTO app.member(id,name,search_name,activity_status,version) VALUES(?,'keep','keep','ACTIVE',7)",
        existing);
    var first = NEW_MEMBERS.getFirst();
    if (conflict.equals("member"))
      jdbc.update(
          "INSERT INTO app.member(id,name,search_name,activity_status) VALUES(?,?,?,'ACTIVE')",
          UUID.randomUUID(),
          first.name(),
          first.name());
    else
      jdbc.update(
          "INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES(?,?,'keep channel','GROUP',false)",
          UUID.randomUUID(),
          first.youtube());
    assertThatThrownBy(() -> cfg.target("21").load().migrate())
        .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    assertThat(
            jdbc.queryForMap(
                "SELECT debut_date,birthday_month,birthday_day,version FROM app.member WHERE id=?",
                existing))
        .containsEntry("debut_date", null)
        .containsEntry("birthday_month", null)
        .containsEntry("birthday_day", null)
        .containsEntry("version", 7L);
    for (var member : NEW_MEMBERS)
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM app.member WHERE id=?",
                  Integer.class,
                  UUID.fromString(member.id())))
          .isZero();
    if (conflict.equals("channel"))
      assertThat(
              jdbc.queryForMap(
                  "SELECT member_id,channel_type,collection_enabled FROM app.channel WHERE youtube_id=?",
                  first.youtube()))
          .containsEntry("member_id", null)
          .containsEntry("channel_type", "GROUP")
          .containsEntry("collection_enabled", false);
  }
}
