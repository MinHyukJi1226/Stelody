package com.stelody.statistics;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ViewStatisticsIntegrationTest {
  static final Instant NOW = Instant.parse("2026-10-03T15:20:00Z"); // Oct 4 in KST
  static final UUID MEMBER = id(1), PUBLICATION = id(2);

  static UUID id(int n) {
    return new UUID(0, n);
  }

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", () -> "stelody_app");
    r.add("spring.datasource.password", () -> "test-runtime");
    r.add("spring.flyway.user", () -> "stelody_migrator");
    r.add("spring.flyway.password", () -> "test-migrator");
  }

  @TestConfiguration
  static class FixedTime {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate runtime;
  JdbcTemplate writer;

  @BeforeEach
  void setup() {
    writer =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    writer.execute(
        "TRUNCATE app.member,app.song_entry,app.view_publication,app.collection_run,app.view_snapshot,app.daily_video_view CASCADE");
    writer.execute("INSERT INTO app.catalog_state(singleton) VALUES(true) ON CONFLICT DO NOTHING");
    writer.update(
        "INSERT INTO app.member(id,name,search_name,activity_status) VALUES(?,'test','test','GRADUATED')",
        MEMBER);
    writer.update(
        "INSERT INTO app.view_publication(id,published_at) VALUES(?,?)",
        PUBLICATION,
        Timestamp.from(NOW));
    writer.update("UPDATE app.catalog_state SET view_publication_id=?", PUBLICATION);
    writer.update(
        "INSERT INTO app.collection_run(id,logical_slot,status,attempt,started_at,finished_at,publication_id) VALUES(?,date_trunc('hour',?::timestamptz),'SUCCEEDED',1,?,?,?)",
        id(3),
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        PUBLICATION);
  }

  UUID song(int n, String published) {
    UUID s = id(100 + n), v = id(1000 + n);
    writer.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES(?,'test','test','COVER','PUBLISHED')",
        s);
    writer.update(
        "INSERT INTO app.video(id,song_id,youtube_id,video_kind,availability,source_title,published_at) VALUES(?,?,?,'OFFICIAL_COVER','PUBLIC','test',?::timestamptz)",
        v,
        s,
        "YT%09d".formatted(n),
        published);
    writer.update("UPDATE app.song_entry SET representative_video_id=? WHERE id=?", v, s);
    writer.update(
        "INSERT INTO app.song_member(song_id,member_id,position) VALUES(?,?,0)", s, MEMBER);
    return s;
  }

  void latest(int n, long count, Instant observed) {
    writer.update(
        "INSERT INTO app.published_video_view(publication_id,video_id,view_count,observed_at) VALUES(?,?,?,?)",
        PUBLICATION,
        id(1000 + n),
        count,
        Timestamp.from(observed));
  }

  void daily(int n, String day, long count, Instant observed) {
    writer.update(
        "INSERT INTO app.daily_video_view(video_id,day,view_count,observed_at) VALUES(?,?::date,?,?)",
        id(1000 + n),
        day,
        count,
        Timestamp.from(observed));
  }

  JsonNode request(String path) throws Exception {
    var result =
        mvc.perform(get(path))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void seriesUsesKstDatesRealZeroAndNullGapsAndPreservesDecreases() throws Exception {
    UUID s = song(1, "2020-01-01T00:00:00Z");
    daily(1, "2026-10-02", 0, Instant.parse("2026-10-02T14:50:00Z"));
    daily(1, "2026-10-04", 70, NOW.minusSeconds(60));
    daily(1, "2026-10-01", 100, Instant.parse("2026-10-01T14:50:00Z"));
    daily(1, "2026-09-27", 999, Instant.parse("2026-09-27T14:50:00Z"));
    latest(1, 70, NOW.minusSeconds(60));
    writer.update("UPDATE app.video SET view_collection_started_at='2026-09-01T00:00:00Z'");
    var data = request("/api/v1/songs/" + s + "/views");
    assertThat(data.path("timeZone").asText()).isEqualTo("Asia/Seoul");
    assertThat(data.path("startDate").asText()).isEqualTo("2026-09-28");
    assertThat(data.path("endDate").asText()).isEqualTo("2026-10-04");
    assertThat(data.path("collectionStartedAt").asText()).isEqualTo("2026-09-01T00:00:00Z");
    assertThat(data.path("latestViewCount").asLong()).isEqualTo(70);
    var points = data.path("points");
    assertThat(points.size()).isEqualTo(7);
    assertThat(points.get(3).path("viewCount").asLong()).isEqualTo(100);
    assertThat(points.get(4).path("viewCount").asLong()).isZero();
    assertThat(points.get(4).path("viewCount").isIntegralNumber()).isTrue();
    assertThat(points.get(5).path("viewCount").isNull()).isTrue();
    assertThat(points.get(5).path("observedAt").isNull()).isTrue();
    assertThat(points.get(6).path("viewCount").asLong()).isEqualTo(70);
  }

  @Test
  void noObservationsRemainNullAndThirtyDayWindowIgnoresExpiredAndFutureData() throws Exception {
    UUID s = song(1, "2020-01-01T00:00:00Z");
    daily(1, "2026-09-04", 999, NOW.minusSeconds(31L * 86400));
    daily(1, "2026-10-04", 777, NOW.plusSeconds(1));
    latest(1, 100, NOW.minusSeconds(31L * 86400));
    var data = request("/api/v1/songs/" + s + "/views?days=30");
    assertThat(data.path("startDate").asText()).isEqualTo("2026-09-05");
    assertThat(data.path("points").size()).isEqualTo(30);
    data.path("points").forEach(p -> assertThat(p.path("viewCount").isNull()).isTrue());
    assertThat(data.path("latestViewCount").isNull()).isTrue();
    assertThat(data.path("collectionStartedAt").isNull()).isTrue();
  }

  @Test
  void chartBeforeFirstPublicationShowsOneRealPointAndNoInventedLatestValue() throws Exception {
    UUID s = song(1, "2020-01-01T00:00:00Z");
    writer.execute("UPDATE app.catalog_state SET view_publication_id=NULL");
    daily(1, "2026-10-04", 0, NOW);
    var data = request("/api/v1/songs/" + s + "/views");
    assertThat(data.path("publicationId").isNull()).isTrue();
    assertThat(data.path("latestViewCount").isNull()).isTrue();
    assertThat(data.path("points").get(6).path("viewCount").isIntegralNumber()).isTrue();
    assertThat(data.path("points").get(6).path("viewCount").asLong()).isZero();
    assertThat(data.path("points").get(5).path("viewCount").isNull()).isTrue();
  }

  @Test
  void representativeChangeUsesOnlyNewVideosHistory() throws Exception {
    UUID s = song(1, "2020-01-01T00:00:00Z");
    daily(1, "2026-10-03", 999, NOW.minusSeconds(3600));
    latest(1, 999, NOW.minusSeconds(100));
    writer.update(
        "INSERT INTO app.video(id,song_id,youtube_id,video_kind,availability,source_title,published_at) VALUES(?,?,'newrecord01','OFFICIAL_COVER','PUBLIC','new','2020-01-01')",
        id(1002),
        s);
    daily(2, "2026-10-04", 5, NOW);
    latest(2, 5, NOW);
    writer.update("UPDATE app.song_entry SET representative_video_id=? WHERE id=?", id(1002), s);
    var data = request("/api/v1/songs/" + s + "/views");
    assertThat(data.path("videoId").asText()).isEqualTo(id(1002).toString());
    assertThat(data.path("latestViewCount").asLong()).isEqualTo(5);
    assertThat(data.path("points").get(5).path("viewCount").isNull()).isTrue();
    assertThat(data.path("points").get(6).path("viewCount").asLong()).isEqualTo(5);
  }

  @ParameterizedTest
  @ValueSource(strings = {"HIDDEN", "DRAFT", "PRIVATE", "UNCONFIRMED", "MISSING"})
  void inaccessibleSongsDoNotExposeHistory(String state) throws Exception {
    UUID s = song(1, "2020-01-01T00:00:00Z");
    daily(1, "2026-10-04", 20, NOW);
    switch (state) {
      case "HIDDEN", "DRAFT" -> writer.update("UPDATE app.song_entry SET visibility=?", state);
      case "PRIVATE" -> writer.execute("UPDATE app.video SET availability='PRIVATE'");
      case "UNCONFIRMED" -> writer.execute("UPDATE app.song_member SET confirmed=false");
      case "MISSING" -> s = UUID.randomUUID();
    }
    mvc.perform(get("/api/v1/songs/" + s + "/views")).andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "8", "31", "text"})
  void invalidChartPeriodIsBadRequest(String days) throws Exception {
    mvc.perform(get("/api/v1/songs/" + id(101) + "/views").param("days", days))
        .andExpect(status().isBadRequest());
  }

  @Test
  void runtimeCanReadButCannotRewriteOrDeleteObservationsOrCollectionStart() {
    song(1, "2020-01-01T00:00:00Z");
    daily(1, "2026-10-04", 5, NOW);
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.daily_video_view", Long.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> runtime.execute("UPDATE app.daily_video_view SET view_count=999"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> runtime.execute("UPDATE app.video SET view_collection_started_at=now()"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
