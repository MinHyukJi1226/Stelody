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

@SpringBootTest(
    properties = {
      "stelody.statistics.trending-enabled=true",
      "stelody.statistics.trending-policy-allowed=true"
    })
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

  void sample(int n, long count, Instant observed) {
    writer.update(
        "INSERT INTO app.view_snapshot(video_id,logical_slot,view_count,observed_at) VALUES(?,date_trunc('hour',?::timestamptz),?,?)",
        id(1000 + n),
        Timestamp.from(observed),
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
  void trendingUsesCommonReferenceNearestEarlierSamplesAndDeterministicTies() throws Exception {
    UUID first = song(1, "2020-01-01T00:00:00Z"),
        second = song(2, "2021-01-01T00:00:00Z"),
        third = song(3, "2021-01-01T00:00:00Z");
    for (int n = 1; n <= 3; n++) {
      latest(n, 200, NOW.minusSeconds(600));
      sample(n, 100, NOW.minusSeconds(86400));
      sample(n, 1, NOW.minusSeconds(90000));
    }
    var data = request("/api/v1/songs/trending");
    assertThat(data.path("status").asText()).isEqualTo("READY");
    assertThat(data.path("referenceAt").asText()).isEqualTo(NOW.toString());
    assertThat(data.path("metricSource").asText()).isEqualTo("STELODY_VIEW_GROWTH");
    assertThat(data.path("items").size()).isEqualTo(3);
    assertThat(data.path("items").get(0).path("song").path("id").asText())
        .isEqualTo(third.toString());
    assertThat(data.path("items").get(1).path("song").path("id").asText())
        .isEqualTo(second.toString());
    assertThat(data.path("items").get(2).path("song").path("id").asText())
        .isEqualTo(first.toString());
    assertThat(data.path("items").get(0).path("increase").asLong()).isEqualTo(100);
    assertThat(request("/api/v1/songs/trending?size=1").path("items").size()).isEqualTo(1);
  }

  @Test
  void trendingIncludesOneHourBoundaryAndRejectsLateOldMissingZeroNegativeOrHiddenSamples()
      throws Exception {
    for (int n = 1; n <= 10; n++) song(n, "2020-01-01T00:00:00Z");
    latest(1, 200, NOW.minusSeconds(3600));
    sample(1, 100, NOW.minusSeconds(90000)); // inclusive
    latest(2, 200, NOW.minusSeconds(3601));
    sample(2, 100, NOW.minusSeconds(86400));
    latest(3, 200, NOW.plusSeconds(1));
    sample(3, 100, NOW.minusSeconds(86400));
    latest(4, 200, NOW);
    sample(4, 100, NOW.minusSeconds(90001));
    latest(5, 200, NOW);
    sample(5, 100, NOW.minusSeconds(86399));
    latest(6, 200, NOW); // no baseline
    latest(7, 100, NOW);
    sample(7, 100, NOW.minusSeconds(86400));
    latest(8, 90, NOW);
    sample(8, 100, NOW.minusSeconds(86400));
    latest(9, 999, NOW);
    sample(9, 0, NOW.minusSeconds(86400));
    writer.update("UPDATE app.song_entry SET visibility='HIDDEN' WHERE id=?", id(109));
    sample(10, 0, NOW.minusSeconds(86400)); // no current count
    var items = request("/api/v1/songs/trending").path("items");
    assertThat(items.size()).isEqualTo(1);
    assertThat(items.get(0).path("song").path("id").asText()).isEqualTo(id(101).toString());
    assertThat(
            writer.queryForObject(
                "SELECT view_count FROM app.view_snapshot WHERE video_id=?", Long.class, id(1008)))
        .isEqualTo(100);
  }

  @Test
  void partialFailedCollectionDoesNotReplaceCompletedRanking() throws Exception {
    song(1, "2020-01-01T00:00:00Z");
    latest(1, 200, NOW.minusSeconds(100));
    sample(1, 100, NOW.minusSeconds(86400));
    sample(1, 999, NOW.minusSeconds(50));
    writer.update(
        "INSERT INTO app.collection_run(id,logical_slot,status,attempt,started_at) VALUES(?,date_trunc('hour',?::timestamptz)+interval '1 hour','FAILED',1,?)",
        id(4),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    assertThat(request("/api/v1/songs/trending").path("items").get(0).path("increase").asLong())
        .isEqualTo(100);
    writer.execute(
        "UPDATE app.collection_run SET status='RUNNING' WHERE publication_id IS NOT NULL");
    var data = request("/api/v1/songs/trending");
    assertThat(data.path("status").asText()).isEqualTo("PENDING");
    assertThat(data.path("items").size()).isZero();
  }

  @Test
  void stalePublicationDoesNotServeRankingAndNoPublicationIsPending() throws Exception {
    writer.update(
        "UPDATE app.view_publication SET published_at=?", Timestamp.from(NOW.minusSeconds(3601)));
    assertThat(request("/api/v1/songs/trending").path("status").asText()).isEqualTo("STALE");
    writer.execute("UPDATE app.catalog_state SET view_publication_id=NULL");
    assertThat(request("/api/v1/songs/trending").path("status").asText()).isEqualTo("PENDING");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "51", "text"})
  void invalidRankingSizeIsBadRequest(String size) throws Exception {
    mvc.perform(get("/api/v1/songs/trending").param("size", size))
        .andExpect(status().isBadRequest());
  }

  @Test
  void runtimeCanReadButCannotRewriteOrDeleteObservationsOrCollectionStart() {
    song(1, "2020-01-01T00:00:00Z");
    daily(1, "2026-10-04", 5, NOW);
    sample(1, 5, NOW);
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.daily_video_view", Long.class))
        .isEqualTo(1);
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.view_snapshot", Long.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> runtime.execute("UPDATE app.daily_video_view SET view_count=999"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> runtime.execute("DELETE FROM app.view_snapshot"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> runtime.execute("UPDATE app.video SET view_collection_started_at=now()"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
