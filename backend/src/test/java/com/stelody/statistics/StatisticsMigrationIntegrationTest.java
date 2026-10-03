package com.stelody.statistics;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.util.Map;
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
class StatisticsMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @Test
  void upgradesV12AndBackfillsKnownStartWithoutChangingSamplesAndSeparatesWriters() {
    var cfg =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("12").load().migrate();
    var source =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    var owner = new JdbcTemplate(source);
    owner.execute(
        """
        INSERT INTO app.song_entry(id,title,search_title,song_type)
        VALUES('00000000-0000-0000-0000-000000000001','manual','manual','COVER');
        INSERT INTO app.video(id,song_id,youtube_id,video_kind,availability,source_title)
        VALUES('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',
          'abcdefghijk','OFFICIAL_COVER','PUBLIC','source');
        INSERT INTO app.daily_video_view(video_id,day,observed_at,view_count)
        VALUES('00000000-0000-0000-0000-000000000002','2026-10-01','2026-10-01T01:00:00Z',5);
        INSERT INTO app.view_snapshot(video_id,logical_slot,observed_at,view_count)
        VALUES('00000000-0000-0000-0000-000000000002','2026-10-02T01:00:00Z','2026-10-02T01:10:00Z',9);
        """);
    assertThat(cfg.target("13").load().migrate().migrationsExecuted).isEqualTo(1);
    assertThat(cfg.load().migrate().migrationsExecuted).isZero();
    var runtime =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    assertThat(
            runtime
                .queryForObject("SELECT view_collection_started_at FROM app.video", Timestamp.class)
                .toInstant()
                .toString())
        .isEqualTo("2026-10-01T01:00:00Z");
    assertThat(runtime.queryForObject("SELECT view_count FROM app.daily_video_view", Long.class))
        .isEqualTo(5);
    assertThat(runtime.queryForObject("SELECT view_count FROM app.view_snapshot", Long.class))
        .isEqualTo(9);
    assertThatThrownBy(
            () -> runtime.execute("UPDATE app.video SET view_collection_started_at=now()"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    new ResourceDatabasePopulator(
            new ClassPathResource("collector-grants.sql"),
            new ClassPathResource("statistics-collector-grants.sql"))
        .execute(source);
    var collector =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_collector", "test-collector"));
    collector.execute("UPDATE app.video SET view_collection_started_at=view_collection_started_at");
    assertThatThrownBy(() -> collector.execute("UPDATE app.video SET published_at=now()"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> collector.queryForList("SELECT * FROM app.playlist"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
