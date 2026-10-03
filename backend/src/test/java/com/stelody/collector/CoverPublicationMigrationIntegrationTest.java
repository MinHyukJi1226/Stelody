package com.stelody.collector;

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
class CoverPublicationMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV18WithoutCollectorAndPreservesManualSongsAndIgnoredIds() {
    var cfg =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("18").load().migrate();
    var owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    owner.execute(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility,version) VALUES(gen_random_uuid(),'manual title','manual title','COVER','HIDDEN',7)");
    owner.execute(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES('00000000-0000-0000-0000-000000000001','UCaaaaaaaaaaaaaaaaaaaaaa','official','GROUP',true)");
    owner.execute(
        "INSERT INTO app.review_item(id,youtube_id,channel_id,source_observed_at,availability,disposition,suggested_type,rule_version,decision_reason,first_seen_at,review_status,review_note) VALUES(gen_random_uuid(),'abcdefghijk','00000000-0000-0000-0000-000000000001',now(),'PUBLIC','REVIEW','COVER','title-v2:0','COVER_REQUIRES_PARTICIPANT_REVIEW',now(),'IGNORED','manual ignore')");
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    admin.execute("DROP OWNED BY stelody_collector");
    admin.execute("DROP ROLE stelody_collector");
    assertThat(cfg.target("19").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(cfg.load().migrate().migrationsExecuted).isZero();
    var web =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    assertThat(
            web.queryForObject("SELECT title||'/'||visibility FROM app.song_entry", String.class))
        .isEqualTo("manual title/HIDDEN");
    assertThat(web.queryForObject("SELECT version FROM app.song_entry", Long.class)).isEqualTo(7);
    assertThat(
            web.queryForObject(
                "SELECT review_status||'/'||review_note FROM app.review_item", String.class))
        .isEqualTo("IGNORED/manual ignore");
    assertThat(
            web.queryForObject("SELECT enabled FROM app.cover_publication_control", Boolean.class))
        .isFalse();
    assertThat(
            web.queryForObject("SELECT count(*) FROM app.cover_auto_registration", Integer.class))
        .isZero();
    assertThatThrownBy(() -> web.execute("DELETE FROM app.cover_auto_registration"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                web.queryForList(
                    "SELECT app.publish_discovered_cover(NULL,now(),NULL,0,'x',true,'solo-credit-v1',NULL)"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    web.execute("UPDATE app.cover_publication_control SET enabled=true,version=version+1");
    assertThat(web.queryForObject("SELECT version FROM app.cover_publication_control", Long.class))
        .isEqualTo(1);
  }
}
