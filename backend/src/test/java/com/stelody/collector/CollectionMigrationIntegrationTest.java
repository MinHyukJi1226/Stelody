package com.stelody.collector;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class CollectionMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV7WithoutCollectorLoginAndPreservesPrivateDataAndManualMetadata() {
    var config =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    config.target("7").load().migrate();
    var owner =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    var jdbc = new JdbcTemplate(owner);
    jdbc.execute(
        """
        INSERT INTO app.app_user(id, google_subject, email)
        VALUES ('00000000-0000-0000-0000-000000000001', 'migration', 'test@example.invalid');
        INSERT INTO app.song_entry(id, title, search_title, song_type)
        VALUES ('00000000-0000-0000-0000-000000000002', 'manual', 'manual', 'COVER');
        INSERT INTO app.video(id, song_id, youtube_id, video_kind, availability, source_title, thumbnail_url)
        VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002',
          'YT000000001', 'OFFICIAL_COVER', 'PUBLIC', 'existing', 'https://manual.invalid/image');
        INSERT INTO app.favorite(user_id, song_id)
        VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002');
        INSERT INTO app.playlist(id, user_id, name, created_at, updated_at)
        VALUES ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', 'private', now(), now());
        INSERT INTO app.playlist_item(id, playlist_id, song_id, position, added_at)
        VALUES ('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000004',
          '00000000-0000-0000-0000-000000000002', 0, now());
        INSERT INTO session.spring_session(primary_id, session_id, creation_time, last_access_time,
          max_inactive_interval, expiry_time)
        VALUES ('existing-primary', 'existing-session', 1, 1, 1800, 1800001);
        """);
    assertThat(config.target("8").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(config.load().migrate().migrationsExecuted).isZero();
    var runtime =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    for (String table :
        new String[] {
          "app.app_user",
          "app.song_entry",
          "app.favorite",
          "app.playlist",
          "app.playlist_item",
          "session.spring_session"
        })
      assertThat(runtime.queryForObject("SELECT count(*) FROM " + table, Long.class)).isEqualTo(1);
    assertThat(runtime.queryForObject("SELECT thumbnail_url FROM app.video", String.class))
        .isEqualTo("https://manual.invalid/image");
    assertThat(runtime.queryForObject("SELECT source_title FROM app.video", String.class))
        .isEqualTo("existing");
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.collection_run", Long.class))
        .isZero();
    assertThatThrownBy(
            () -> runtime.execute("UPDATE app.collection_control SET owner_token = NULL"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    var collector =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_collector", "test-collector"));
    assertThatThrownBy(() -> collector.queryForList("SELECT * FROM app.video"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    new ResourceDatabasePopulator(new ClassPathResource("collector-grants.sql")).execute(owner);
    assertThat(collector.queryForObject("SELECT count(*) FROM app.video", Long.class)).isEqualTo(1);
    assertThatThrownBy(() -> collector.queryForList("SELECT * FROM app.playlist"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> collector.execute("UPDATE app.video SET thumbnail_url = 'overwrite'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
