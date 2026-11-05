package com.stelody.milestone;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.service.CollectionLock;
import com.zaxxer.hikari.*;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "stelody.milestones.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class MilestoneIntegrationTest {
  static final Instant NOW = Instant.parse("2026-11-05T10:01:00Z");
  static final UUID MEMBER = new UUID(0, 1), CHANNEL = new UUID(0, 2), PUB = new UUID(0, 3);
  static final String YT_CHANNEL = "UC" + "a".repeat(22);

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
  static class Time {
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
  HikariDataSource source;
  MilestoneRepository repo;
  WireMockServer server;

  @BeforeEach
  void setup() {
    var owner =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    writer = new JdbcTemplate(owner);
    writer.execute(
        "TRUNCATE app.member,app.channel,app.song_entry,app.view_publication,app.collection_run,app.view_snapshot CASCADE");
    writer.execute("UPDATE app.view_milestone_control SET retry_after=NULL,last_error_code=NULL");
    new ResourceDatabasePopulator(new ClassPathResource("collector-grants.sql")).execute(owner);
    var cfg = new HikariConfig();
    cfg.setJdbcUrl(postgres.getJdbcUrl());
    cfg.setUsername("stelody_collector");
    cfg.setPassword("test-collector");
    cfg.setMaximumPoolSize(2);
    source = new HikariDataSource(cfg);
    repo = new MilestoneRepository(source);
    writer.update(
        "INSERT INTO app.member(id,name,search_name,activity_status) VALUES(?,'member','member','ACTIVE')",
        MEMBER);
    writer.update(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES(?,?,'channel','MEMBER',true)",
        CHANNEL,
        YT_CHANNEL);
    writer.execute("INSERT INTO app.catalog_state(singleton) VALUES(true) ON CONFLICT DO NOTHING");
    writer.update("INSERT INTO app.view_publication(id,published_at) VALUES(?,?)", PUB, ts(NOW));
    writer.update("UPDATE app.catalog_state SET view_publication_id=? WHERE singleton", PUB);
    writer.update(
        "INSERT INTO app.collection_run(id,logical_slot,status,attempt,started_at,finished_at,publication_id) VALUES(?,?,'SUCCEEDED',1,?,?,?)",
        UUID.randomUUID(),
        ts(NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS)),
        ts(NOW),
        ts(NOW),
        PUB);
    server = new WireMockServer(0);
    server.start();
  }

  @AfterEach
  void close() {
    server.stop();
    source.close();
  }

  static UUID song(int n) {
    return new UUID(1, n);
  }

  static UUID video(int n) {
    return new UUID(2, n);
  }

  static String youtube(int n) {
    return "YT%09d".formatted(n);
  }

  static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }

  void seed(int n) {
    writer.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES(?,'test','test','COVER','PUBLISHED')",
        song(n));
    writer.update(
        "INSERT INTO app.song_member(song_id,member_id,position) VALUES(?,?,0)", song(n), MEMBER);
    writer.update(
        "INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title,published_at) VALUES(?,?,?,?,'OFFICIAL_COVER','PUBLIC','test',?)",
        video(n),
        song(n),
        youtube(n),
        CHANNEL,
        ts(NOW.minusSeconds(86400)));
    writer.update(
        "UPDATE app.song_entry SET representative_video_id=? WHERE id=?", video(n), song(n));
  }

  void observe(int n, long views, Instant at) {
    repo.observe(
        new MilestoneRepository.Observation(
            video(n), youtube(n), YT_CHANNEL, views, at, null, null));
  }

  void latest(int n, long views, Instant at) {
    writer.update(
        "INSERT INTO app.published_video_view(publication_id,video_id,view_count,observed_at) VALUES(?,?,?,?)",
        PUB,
        video(n),
        views,
        ts(at));
  }

  void baseline(int n, long views, Instant at) {
    writer.update(
        "INSERT INTO app.view_snapshot(video_id,logical_slot,view_count,observed_at) VALUES(?,?,?,?)",
        video(n),
        ts(at.truncatedTo(java.time.temporal.ChronoUnit.HOURS)),
        views,
        ts(at));
  }

  long events() {
    return writer.queryForObject("SELECT count(*) FROM app.view_milestone_event", Long.class);
  }

  @Test
  void snapshotImporterOnlyUsesCompletedPublicationsAndKeepsPredictionBaseline() {
    seed(1);
    latest(1, 99_000, NOW);
    baseline(1, 95_000, NOW.minusSeconds(3600));
    var o = repo.published(NOW).getFirst();
    assertThat(o.previousViews()).isEqualTo(95_000);
    repo.observe(o);
    assertThat(repo.due(NOW)).isEmpty();
    assertThat(repo.due(NOW.plusSeconds(600))).hasSize(1);
    writer.execute("UPDATE app.collection_run SET status='FAILED'");
    assertThat(repo.published(NOW)).isEmpty();
  }

  @Test
  void slowFlatOrDecliningCountsBackOffInsteadOfPollingEachMinute() {
    seed(1);
    var at = NOW.minusSeconds(60);
    repo.observe(
        new MilestoneRepository.Observation(
            video(1), youtube(1), YT_CHANNEL, 99_900, at, 95_000L, at.minusSeconds(3600)));
    assertThat(repo.due(NOW)).hasSize(1);
    repo.observe(
        new MilestoneRepository.Observation(
            video(1), youtube(1), YT_CHANNEL, 99_900, NOW, 95_000L, NOW.minusSeconds(3600)));
    assertThat(repo.due(NOW.plusSeconds(60))).isEmpty();
    assertThat(repo.due(NOW.plusSeconds(600))).hasSize(1);
    observe(1, 99_900, NOW.plusSeconds(600));
    assertThat(repo.due(NOW.plusSeconds(1200))).isEmpty();
    assertThat(repo.due(NOW.plusSeconds(4200))).hasSize(1);
  }

  @Test
  void runtimeCannotWriteStateAndCollectorCannotReadAccounts() {
    assertThatThrownBy(() -> runtime.execute("DELETE FROM app.view_milestone_state"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> new JdbcTemplate(source).execute("SELECT * FROM app.app_user"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  MilestoneWorker worker() {
    return worker(NOW);
  }

  MilestoneWorker worker(Instant time) {
    var settings =
        new CollectorSettings(
            true,
            postgres.getJdbcUrl(),
            "stelody_collector",
            "test-collector",
            "test-key",
            URI.create(server.baseUrl() + "/youtube/v3"),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofMinutes(1),
            1,
            Duration.ZERO);
    return new MilestoneWorker(source, settings, Clock.fixed(time, ZoneOffset.UTC), d -> {});
  }

  String response(long count, String channel) {
    return mapper.writeValueAsString(
        Map.of(
            "kind",
            "youtube#videoListResponse",
            "items",
            List.of(
                Map.of(
                    "id",
                    youtube(1),
                    "snippet",
                    Map.of(
                        "channelId",
                        channel,
                        "title",
                        "test",
                        "publishedAt",
                        "2026-11-01T00:00:00Z"),
                    "contentDetails",
                    Map.of("duration", "PT2M"),
                    "status",
                    Map.of("privacyStatus", "public", "embeddable", true),
                    "statistics",
                    Map.of("viewCount", Long.toString(count))))));
  }

  void dueSong() {
    seed(1);
    observe(1, 99_900, NOW.minusSeconds(3600));
  }

  @Test
  void workerPollsOnlyDueSongsAndDoesNotModifyHourlyPublication() {
    dueSong();
    server.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/youtube/v3/videos"))
            .willReturn(okJson(response(100_001, YT_CHANNEL))));
    worker().tick();
    assertThat(writer.queryForObject("SELECT view_count FROM app.view_milestone_state", Long.class))
        .isEqualTo(100_001);
    assertThat(
            writer.queryForObject("SELECT view_publication_id FROM app.catalog_state", UUID.class))
        .isEqualTo(PUB);
    worker().tick();
    server.verify(1, getRequestedFor(urlPathEqualTo("/youtube/v3/videos")));
  }

  @Test
  void sharedLockBlocksExtraPollingAndQuotaFailureHasPersistentCooldown() throws Exception {
    dueSong();
    try (var lock = CollectionLock.acquire(source)) {
      assertThat(lock).isNotNull();
      worker().tick();
      server.verify(0, anyRequestedFor(anyUrl()));
    }
    server.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}")));
    worker().tick();
    worker().tick();
    server.verify(1, getRequestedFor(anyUrl()));
    assertThat(events()).isZero();
    assertThat(repo.due(NOW)).isEmpty();
  }

  @Test
  void unavailableOrMissingCountsHideNewsWithoutAdvancingTheCountObservation() {
    dueSong();
    server.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(anyUrl())
            .willReturn(okJson("{\"kind\":\"youtube#videoListResponse\",\"items\":[]}")));
    worker().tick();
    assertThat(events()).isZero();
    assertThat(
            writer
                .queryForObject("SELECT observed_at FROM app.view_milestone_state", Timestamp.class)
                .toInstant())
        .isEqualTo(NOW.minusSeconds(3600));
  }

  @Test
  void onlyFiftyDueVideosAreSelectedAndServicedVideosYieldToTheRest() {
    for (int n = 1; n <= 51; n++) {
      seed(n);
      observe(n, 99_900, NOW.minusSeconds(3600));
    }
    var first = repo.due(NOW);
    assertThat(first).hasSize(50);
    for (var t : first) repo.observe(repo.polled(t, 100_001, NOW));
    assertThat(repo.due(NOW)).hasSize(1);
    assertThat(repo.due(NOW).getFirst().videoId())
        .isNotIn(first.stream().map(MilestoneRepository.Target::videoId).toList());
  }

  @Test
  void firstMinuteOfTheHourIsReservedForTheRegularCollector() {
    dueSong();
    worker(NOW.minusSeconds(60)).tick();
    server.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void changedIdentityOrPublicationAfterSelectionCannotCreateNews() {
    dueSong();
    var observation =
        repo.polled(new MilestoneRepository.Target(video(1), youtube(1), YT_CHANNEL), 100_001, NOW);
    writer.update("UPDATE app.video SET youtube_id='changed1234' WHERE id=?", video(1));
    repo.observe(observation);
    assertThat(events()).isZero();
    writer.update("UPDATE app.video SET youtube_id=? WHERE id=?", youtube(1), video(1));
    writer.update("UPDATE app.song_entry SET visibility='HIDDEN' WHERE id=?", song(1));
    repo.observe(observation);
    assertThat(events()).isZero();
  }

  @Test
  void malformedChannelCannotCreateAchievements() {
    dueSong();
    server.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(anyUrl())
            .willReturn(okJson(response(100_001, "UC" + "b".repeat(22)))));
    worker().tick();
    assertThat(events()).isZero();
    assertThat(repo.due(NOW)).isEmpty();
  }
}
