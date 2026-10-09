package com.stelody.collector;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.collector.config.CollectorConfiguration;
import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.domain.VideoObservation;
import com.stelody.collector.repository.CollectionRepository;
import com.stelody.collector.scheduling.CollectionScheduleState;
import com.stelody.collector.service.CollectionLock;
import com.stelody.collector.service.CollectorExecution;
import com.stelody.collector.service.VideoCollector;
import com.stelody.song.repository.SongRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class VideoCollectorIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "stelody_app");
    registry.add("spring.datasource.password", () -> "test-runtime");
    registry.add("spring.flyway.user", () -> "stelody_migrator");
    registry.add("spring.flyway.password", () -> "test-migrator");
  }

  static final Instant NOW = Instant.parse("2026-10-01T00:17:00Z");
  static final String CHANNEL = "UC" + "a".repeat(22);
  static final UUID CHANNEL_ID = new UUID(0, 1), MEMBER = new UUID(0, 2);
  @Autowired JdbcTemplate runtime;
  @Autowired ObjectMapper mapper;
  JdbcTemplate writer;
  HikariDataSource source;
  WireMockServer server;

  @BeforeEach
  void setup() {
    var owner =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    writer = new JdbcTemplate(owner);
    writer.execute(
        "TRUNCATE app.app_user, app.member, app.channel, app.song_entry, app.view_publication, app.collection_run, app.view_snapshot, app.daily_video_view, session.spring_session CASCADE");
    writer.execute("UPDATE app.collection_control SET owner_token = NULL");
    writer.execute("INSERT INTO app.catalog_state(singleton) VALUES(true) ON CONFLICT DO NOTHING");
    new ResourceDatabasePopulator(new ClassPathResource("collector-grants.sql")).execute(owner);
    new ResourceDatabasePopulator(new ClassPathResource("statistics-collector-grants.sql"))
        .execute(owner);
    writer.update(
        "INSERT INTO app.member(id, name, search_name, activity_status) VALUES (?, 'test', 'test', 'ACTIVE')",
        MEMBER);
    writer.update(
        "INSERT INTO app.channel(id, youtube_id, name, channel_type, collection_enabled) VALUES (?, ?, 'test', 'GROUP', true)",
        CHANNEL_ID,
        CHANNEL);
    var config = new HikariConfig();
    config.setJdbcUrl(postgres.getJdbcUrl());
    config.setUsername("stelody_collector");
    config.setPassword("test-collector");
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(1000);
    source = new HikariDataSource(config);
    server = new WireMockServer(0);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
    source.close();
  }

  UUID song(int n) {
    return new UUID(1, n);
  }

  UUID video(int n) {
    return new UUID(2, n);
  }

  String youtube(int n) {
    return "YT%09d".formatted(n);
  }

  void seed(int count) {
    for (int i = 1; i <= count; i++) {
      writer.update(
          "INSERT INTO app.song_entry(id, title, search_title, song_type, visibility) VALUES (?, 'manual title', 'manual title', 'COVER', 'PUBLISHED')",
          song(i));
      writer.update(
          "INSERT INTO app.song_member(song_id, member_id, position) VALUES (?, ?, 0)",
          song(i),
          MEMBER);
      writer.update(
          """
          INSERT INTO app.video(id, song_id, youtube_id, channel_id, video_kind, availability,
            source_title, published_at, thumbnail_url)
          VALUES (?, ?, ?, ?, 'OFFICIAL_COVER', 'PUBLIC', 'previous source', '2020-01-01T00:00:00Z', 'https://manual.invalid/override.jpg')
          """,
          video(i),
          song(i),
          youtube(i),
          CHANNEL_ID);
      writer.update(
          "UPDATE app.song_entry SET representative_video_id = ? WHERE id = ?", video(i), song(i));
    }
  }

  String response(List<Integer> numbers, long base) {
    var items = new ArrayList<Object>();
    for (int n : numbers)
      items.add(
          Map.of(
              "id",
              youtube(n),
              "snippet",
              Map.of(
                  "channelId",
                  CHANNEL,
                  "title",
                  "source " + n,
                  "publishedAt",
                  "2021-01-01T00:00:00Z",
                  "thumbnails",
                  Map.of("high", Map.of("url", "https://i.ytimg.com/source.jpg"))),
              "contentDetails",
              Map.of("duration", "PT2M"),
              "status",
              Map.of("privacyStatus", "public", "embeddable", true),
              "statistics",
              Map.of("viewCount", Long.toString(base + n))));
    return mapper.writeValueAsString(Map.of("kind", "youtube#videoListResponse", "items", items));
  }

  List<Integer> numbers(int start, int end) {
    return java.util.stream.IntStream.rangeClosed(start, end).boxed().toList();
  }

  void success(List<Integer> ids, long base) {
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .withQueryParam(
                "id", equalTo(String.join(",", ids.stream().map(this::youtube).toList())))
            .willReturn(okJson(response(ids, base))));
  }

  CollectorSettings settings(Duration budget, int attempts) {
    return new CollectorSettings(
        true,
        postgres.getJdbcUrl(),
        "stelody_collector",
        "test-collector",
        "test-api-key",
        URI.create(server.baseUrl()),
        Duration.ofMillis(100),
        Duration.ofSeconds(2),
        budget,
        attempts,
        Duration.ofMillis(1));
  }

  VideoCollector collector(Instant time) {
    return new VideoCollector(
        source, settings(Duration.ofSeconds(30), 1), Clock.fixed(time, ZoneOffset.UTC), d -> {});
  }

  long count(String table) {
    return writer.queryForObject("SELECT count(*) FROM app." + table, Long.class);
  }

  UUID publication() {
    return runtime.queryForObject("SELECT view_publication_id FROM app.catalog_state", UUID.class);
  }

  void oldPublication() {
    writer.update(
        "INSERT INTO app.view_publication(id, published_at) VALUES (?, ?)",
        new UUID(3, 1),
        java.sql.Timestamp.from(NOW.minusSeconds(3600)));
    writer.update(
        "INSERT INTO app.published_video_view(publication_id, video_id, view_count, observed_at) VALUES (?, ?, 10, ?)",
        new UUID(3, 1),
        video(1),
        java.sql.Timestamp.from(NOW.minusSeconds(3600)));
    writer.update("UPDATE app.catalog_state SET view_publication_id = ?", new UUID(3, 1));
  }

  @Test
  void successPublishesOncePreservesOverridesAndRecordsRealObservations() {
    seed(2);
    oldPublication();
    success(numbers(1, 2), 100);
    writer.update("UPDATE app.song_entry SET visibility = 'HIDDEN' WHERE id = ?", song(2));
    var result = collector(NOW).collect();
    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(publication()).isNotEqualTo(new UUID(3, 1));
    assertThat(count("view_snapshot")).isEqualTo(2);
    assertThat(count("daily_video_view")).isEqualTo(2);
    assertThat(count("view_publication")).isEqualTo(1);
    assertThat(
            runtime.queryForObject(
                "SELECT title FROM app.song_entry WHERE id = ?", String.class, song(1)))
        .isEqualTo("manual title");
    assertThat(
            runtime.queryForObject(
                "SELECT visibility FROM app.song_entry WHERE id = ?", String.class, song(2)))
        .isEqualTo("HIDDEN");
    var row = runtime.queryForMap("SELECT * FROM app.video WHERE id = ?", video(1));
    assertThat(row.get("source_title")).isEqualTo("source 1");
    assertThat(row.get("thumbnail_url")).isEqualTo("https://manual.invalid/override.jpg");
    assertThat(row.get("source_thumbnail_url")).isEqualTo("https://i.ytimg.com/source.jpg");
    var songs = new SongRepository(JdbcClient.create(runtime.getDataSource()));
    assertThat(songs.find(song(1), songs.publication()).orElseThrow().viewCount()).isEqualTo(101);
    assertThat(songs.find(song(1), songs.publication()).orElseThrow().publishedAt())
        .isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    assertThat(songs.find(song(2), songs.publication())).isEmpty();
    assertThat(collector(NOW.plusSeconds(300)).collect().status()).isEqualTo("SKIPPED_COMPLETED");
    assertThat(count("view_snapshot")).isEqualTo(2);
    server.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void fullBatchFitsTransactionBudgetWithRemoteDatabaseLatency() throws Exception {
    seed(50);
    var repo = new CollectionRepository(source);
    UUID token = UUID.randomUUID();
    var run = repo.begin(token, NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS), NOW);
    var batch = repo.pending(run.id());
    var observations = new java.util.HashMap<String, VideoObservation>();
    for (int n = 1; n <= 50; n++)
      observations.put(
          youtube(n),
          new VideoObservation(
              youtube(n), CHANNEL, "PUBLIC", "fresh", NOW, null, 240L, true, 100L));
    var delayed =
        new DelegatingDataSource(source) {
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection = source.getConnection();
            return (java.sql.Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class},
                    (proxy, method, args) -> {
                      try {
                        var result = method.invoke(connection, args);
                        if (method.getName().equals("prepareStatement")) {
                          return java.lang.reflect.Proxy.newProxyInstance(
                              getClass().getClassLoader(),
                              new Class<?>[] {java.sql.PreparedStatement.class},
                              (statementProxy, statementMethod, statementArgs) -> {
                                if (statementMethod.getName().startsWith("execute"))
                                  Thread.sleep(200);
                                try {
                                  return statementMethod.invoke(result, statementArgs);
                                } catch (java.lang.reflect.InvocationTargetException e) {
                                  throw e.getCause();
                                }
                              });
                        }
                        return result;
                      } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                      }
                    });
          }
        };
    new CollectionRepository(delayed).save(token, run, batch, observations, NOW);
    repo.publish(token, run, NOW);
    assertThat(count("view_snapshot")).isEqualTo(50);
    assertThat(count("daily_video_view")).isEqualTo(50);
    assertThat(writer.queryForObject("SELECT status FROM app.collection_run", String.class))
        .isEqualTo("SUCCEEDED");
  }

  @Test
  void databaseFailureLateInFullBatchRollsBackEveryObservationAndPreservesPublication() {
    seed(50);
    oldPublication();
    success(numbers(1, 50), 100);
    writer.execute(
        "ALTER TABLE app.daily_video_view ADD CONSTRAINT collector_test_failure CHECK (video_id <> '"
            + video(50)
            + "')");
    try {
      var result = collector(NOW).collect();
      assertThat(result.code()).isEqualTo("DATABASE_ERROR");
      assertThat(publication()).isEqualTo(new UUID(3, 1));
      assertThat(count("view_snapshot")).isZero();
      assertThat(count("daily_video_view")).isZero();
      assertThat(
              writer.queryForObject(
                  "SELECT count(*) FROM app.collection_target WHERE outcome = 'PENDING'",
                  Long.class))
          .isEqualTo(50);
      assertThat(
              writer.queryForObject(
                  "SELECT count(*) FROM app.video WHERE source_title = 'previous source' AND view_collection_started_at IS NULL",
                  Long.class))
          .isEqualTo(50);
    } finally {
      writer.execute("ALTER TABLE app.daily_video_view DROP CONSTRAINT collector_test_failure");
    }
  }

  @Test
  void sourceMetadataIsUsedWhenManualDisplayFieldsAreAbsent() {
    seed(1);
    success(List.of(1), 100);
    writer.update(
        "UPDATE app.video SET published_at = NULL, thumbnail_url = NULL WHERE id = ?", video(1));
    assertThat(collector(NOW).collect().status()).isEqualTo("SUCCEEDED");
    var songs = new SongRepository(JdbcClient.create(runtime.getDataSource()));
    var value = songs.find(song(1), songs.publication()).orElseThrow();
    assertThat(value.thumbnailUrl()).isEqualTo("https://i.ytimg.com/source.jpg");
    assertThat(value.publishedAt()).isEqualTo(Instant.parse("2021-01-01T00:00:00Z"));
  }

  @Test
  void partialFailurePreservesPublicationAndNextExecutionResumesOnlyPendingBatch() {
    seed(51);
    oldPublication();
    success(numbers(1, 50), 100);
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .withQueryParam("id", equalTo(youtube(51)))
            .willReturn(aResponse().withStatus(503)));
    var result = collector(NOW).collect();
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.code()).isEqualTo("API_HTTP_503");
    assertThat(publication()).isEqualTo(new UUID(3, 1));
    assertThat(count("view_snapshot")).isEqualTo(50);
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.collection_target WHERE outcome = 'PENDING'", Long.class))
        .isEqualTo(1);
    server.resetAll();
    success(List.of(51), 100);
    var resumed = collector(NOW.plusSeconds(3600)).collect();
    assertThat(resumed.runId()).isEqualTo(result.runId());
    assertThat(resumed.status()).isEqualTo("SUCCEEDED");
    assertThat(count("view_snapshot")).isEqualTo(51);
    assertThat(count("collection_run")).isEqualTo(1);
    assertThat(writer.queryForObject("SELECT attempt FROM app.collection_run", Integer.class))
        .isEqualTo(2);
    assertThat(
            writer
                .queryForObject(
                    "SELECT logical_slot FROM app.view_snapshot WHERE video_id = ?",
                    java.sql.Timestamp.class,
                    video(51))
                .toInstant())
        .isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS));
    assertThat(
            writer
                .queryForObject(
                    "SELECT observed_at FROM app.view_snapshot WHERE video_id = ?",
                    java.sql.Timestamp.class,
                    video(51))
                .toInstant())
        .isEqualTo(NOW.plusSeconds(3600));
    server.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void apiFailuresDoNotMarkAnyVideoDeletedAndQuotaHasNoRetry() {
    seed(1);
    oldPublication();
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}")));
    var result =
        new VideoCollector(
                source,
                settings(Duration.ofSeconds(30), 3),
                Clock.fixed(NOW, ZoneOffset.UTC),
                d -> {})
            .collect();
    assertThat(result.status()).isEqualTo("QUOTA_EXHAUSTED");
    assertThat(runtime.queryForObject("SELECT availability FROM app.video", String.class))
        .isEqualTo("PUBLIC");
    assertThat(publication()).isEqualTo(new UUID(3, 1));
    assertThat(count("view_snapshot")).isZero();
    server.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void successfulMissingVideoIsUnavailableWithoutFabricatingDeletionOrZero() {
    seed(1);
    oldPublication();
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .willReturn(okJson("{\"kind\":\"youtube#videoListResponse\",\"items\":[]}")));
    assertThat(collector(NOW).collect().status()).isEqualTo("SUCCEEDED");
    assertThat(runtime.queryForObject("SELECT availability FROM app.video", String.class))
        .isEqualTo("UNAVAILABLE");
    assertThat(count("view_snapshot")).isZero();
    assertThat(count("published_video_view")).isZero();
  }

  @Test
  void channelMismatchRollsBackEveryVideoInBatchAndLeavesPublication() {
    seed(2);
    oldPublication();
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .willReturn(
                okJson(
                    response(List.of(1, 2), 100)
                        .replace("source 2", "test")
                        .replace(CHANNEL, "UC" + "b".repeat(22)))));
    var result = collector(NOW).collect();
    assertThat(result.code()).isEqualTo("INVALID_RESPONSE");
    assertThat(count("view_snapshot")).isZero();
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM app.video WHERE source_title = 'previous source'",
                Long.class))
        .isEqualTo(2);
    assertThat(publication()).isEqualTo(new UUID(3, 1));
  }

  @Test
  void onlyEnabledOfficialChannelsAreTargetsAndEmptyRunDoesNotEraseOldViews() {
    seed(1);
    oldPublication();
    writer.execute("UPDATE app.channel SET collection_enabled = false");
    assertThat(collector(NOW).collect().status()).isEqualTo("SUCCEEDED");
    assertThat(count("collection_target")).isZero();
    assertThat(publication()).isEqualTo(new UUID(3, 1));
    writer.execute("UPDATE app.channel SET collection_enabled = true, channel_type = 'EXTERNAL'");
    assertThat(collector(NOW.plusSeconds(3600)).collect().status()).isEqualTo("SUCCEEDED");
    assertThat(count("collection_target")).isZero();
    server.verify(0, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void databaseLockSkipsOverlapAndOwnershipTokenBlocksAStaleWorker() throws Exception {
    seed(1);
    success(List.of(1), 100);
    try (var lock = CollectionLock.acquire(source)) {
      assertThat(lock).isNotNull();
      assertThat(collector(NOW).collect().status()).isEqualTo("SKIPPED_LOCKED");
      assertThat(count("collection_run")).isZero();
    }
    var repo = new CollectionRepository(source);
    UUID old = UUID.randomUUID(), replacement = UUID.randomUUID();
    var run = repo.begin(old, NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS), NOW);
    var resumed = repo.begin(replacement, run.slot(), NOW.plusSeconds(1));
    assertThat(resumed.id()).isEqualTo(run.id());
    assertThatThrownBy(() -> repo.publish(old, run, NOW))
        .isInstanceOfSatisfying(
            CollectionFailure.class, e -> assertThat(e.code()).isEqualTo("LOCK_LOST"));
    assertThatThrownBy(() -> repo.fail(old, run.id(), "FAILED", NOW))
        .isInstanceOf(CollectionFailure.class);
    assertThat(writer.queryForObject("SELECT status FROM app.collection_run", String.class))
        .isEqualTo("RUNNING");
    var batch = repo.pending(run.id());
    repo.save(
        replacement,
        run,
        batch,
        Map.of(
            youtube(1),
            new VideoObservation(youtube(1), CHANNEL, "PUBLIC", "fresh", NOW, null, 1L, true, 10L)),
        NOW.plusSeconds(1));
    repo.publish(replacement, run, NOW.plusSeconds(1));
    assertThat(writer.queryForObject("SELECT status FROM app.collection_run", String.class))
        .isEqualTo("SUCCEEDED");
  }

  @Test
  void disabledChannelDuringRunIsSkippedAndNewerObservationIsNotOverwritten() {
    seed(1);
    var repo = new CollectionRepository(source);
    UUID token = UUID.randomUUID();
    var run = repo.begin(token, NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS), NOW);
    var batch = repo.pending(run.id());
    writer.execute("UPDATE app.channel SET collection_enabled = false");
    repo.save(token, run, batch, Map.of(youtube(1), VideoObservation.unavailable(youtube(1))), NOW);
    assertThat(runtime.queryForObject("SELECT availability FROM app.video", String.class))
        .isEqualTo("PUBLIC");
    assertThat(writer.queryForObject("SELECT outcome FROM app.collection_target", String.class))
        .isEqualTo("SKIPPED");
    assertThat(count("view_snapshot")).isZero();
    repo.publish(token, run, NOW);
  }

  @Test
  void dailySampleUsesLastSuccessfulObservationInKstAndRetentionIsBounded() {
    seed(1);
    success(List.of(1), 100);
    Instant before = Instant.parse("2026-09-30T14:17:00Z");
    assertThat(collector(before).collect().status()).isEqualTo("SUCCEEDED");
    server.resetAll();
    success(List.of(1), 200);
    Instant after = Instant.parse("2026-09-30T15:17:00Z");
    assertThat(collector(after).collect().status()).isEqualTo("SUCCEEDED");
    server.resetAll();
    success(List.of(1), 300);
    assertThat(collector(after.plusSeconds(3600)).collect().status()).isEqualTo("SUCCEEDED");
    assertThat(
            writer.queryForObject(
                "SELECT view_count FROM app.daily_video_view WHERE day = '2026-10-01'", Long.class))
        .isEqualTo(301);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.daily_video_view", Long.class))
        .isEqualTo(2);
    assertThat(
            writer
                .queryForObject(
                    "SELECT view_collection_started_at FROM app.video", java.sql.Timestamp.class)
                .toInstant())
        .isEqualTo(before);
    var repo = new CollectionRepository(source);
    UUID token = UUID.randomUUID();
    repo.begin(
        token,
        NOW.plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.HOURS),
        NOW.plusSeconds(3600));
    repo.cleanup(token, after.plusSeconds(31L * 86400));
    assertThat(count("view_snapshot")).isZero();
    assertThat(count("daily_video_view")).isZero();
    assertThat(count("published_video_view")).isZero();
    assertThat(
            writer
                .queryForObject(
                    "SELECT view_collection_started_at FROM app.video", java.sql.Timestamp.class)
                .toInstant())
        .isEqualTo(before);
    assertThat(runtime.queryForObject("SELECT thumbnail_url FROM app.video", String.class))
        .isEqualTo("https://manual.invalid/override.jpg");
    assertThat(runtime.queryForObject("SELECT source_title FROM app.video", String.class))
        .isEmpty();
    assertThat(runtime.queryForObject("SELECT availability FROM app.video", String.class))
        .isEqualTo("UNAVAILABLE");
  }

  @Test
  void collectorRoleCannotReadPrivateDataOrChangeManualCatalogFields() {
    new CollectionRepository(source).checkPrivileges();
    var jdbc = new JdbcTemplate(source);
    for (String table :
        List.of(
            "app.app_user",
            "app.favorite",
            "app.playlist",
            "app.playlist_item",
            "session.spring_session"))
      assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM " + table))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE app.song_entry SET title = 'forbidden'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE app.video SET thumbnail_url = 'forbidden'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> jdbc.execute("CREATE TABLE app.forbidden(id int)"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> runtime.update("UPDATE app.video SET availability = 'DELETED'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    var broad =
        new VideoCollector(
            runtime.getDataSource(),
            settings(Duration.ofSeconds(30), 1),
            Clock.fixed(NOW, ZoneOffset.UTC),
            d -> {});
    assertThat(broad.collect().code()).isEqualTo("COLLECTOR_ROLE_TOO_BROAD");
  }

  @Test
  void tinyBudgetStopsBeforeCallingApiAndCanResume() {
    seed(1);
    success(List.of(1), 100);
    var collector =
        new VideoCollector(
            source, settings(Duration.ofNanos(1), 1), Clock.fixed(NOW, ZoneOffset.UTC), d -> {});
    assertThat(collector.collect().status()).isEqualTo("TIMED_OUT");
    assertThat(collector(NOW.plusSeconds(1)).collect().status()).isEqualTo("SUCCEEDED");
    server.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void disabledCollectorContextHasNoWebDatabaseSessionOrMigrationBeans() {
    var application = new SpringApplication(CollectorConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles("collector");
    try (var context = application.run("--stelody.collector.enabled=false")) {
      assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
      assertThat(context.getBeansOfType(jakarta.persistence.EntityManagerFactory.class)).isEmpty();
      assertThat(context.getBeansOfType(org.flywaydb.core.Flyway.class)).isEmpty();
      assertThat(context.getBeansOfType(org.springframework.session.SessionRepository.class))
          .isEmpty();
      assertThat(SpringApplication.exit(context)).isZero();
    }
  }

  @Test
  void scheduledCollectionChecksPersistedSuccessAndResumesCurrentStateAfterRestart() {
    seed(1);
    success(List.of(1), 100);
    var before = new CollectionScheduleState(source, false, false);
    assertThat(before.due(NOW.minusSeconds(1)).videos()).isFalse();
    assertThat(before.due(NOW).videos()).isTrue();
    assertThat(collector(NOW).collect().status()).isEqualTo("SUCCEEDED");
    var restarted = new CollectionScheduleState(source, false, false);
    assertThat(restarted.due(NOW.plusSeconds(60)).due()).isFalse();
    assertThat(restarted.due(NOW.plusSeconds(3600)).videos()).isTrue();
  }

  @Test
  void interruptedOlderSlotRemainsDueEvenWhenCurrentSlotIsComplete() {
    seed(1);
    success(List.of(1), 100);
    assertThat(collector(NOW).collect().status()).isEqualTo("SUCCEEDED");
    writer.update(
        "INSERT INTO app.collection_run(id, logical_slot, status, attempt, started_at) VALUES(? ,? ,'FAILED',1,?)",
        UUID.randomUUID(),
        java.sql.Timestamp.from(
            NOW.minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.HOURS)),
        java.sql.Timestamp.from(NOW.minusSeconds(3600)));
    assertThat(new CollectionScheduleState(source, false, false).due(NOW).videos()).isTrue();
  }

  @Test
  void actualChannelScanSatisfiesScheduleAndRetryRequestsRemainDue() {
    var owner =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    new ResourceDatabasePopulator(
            new ClassPathResource("discovery-grants.sql"),
            new ClassPathResource("collection-operations-grants.sql"))
        .execute(owner);
    var schedule = new CollectionScheduleState(source, true, true);
    assertThat(schedule.due(NOW).discovery()).isTrue();
    var id = UUID.randomUUID();
    writer.update(
        "INSERT INTO app.discovery_run(id, mode, started_at, status) VALUES(?, 'NEW', ?, 'FAILED')",
        id,
        java.sql.Timestamp.from(NOW));
    assertThat(schedule.due(NOW).discovery()).isTrue();
    writer.update("UPDATE app.discovery_run SET status='SUCCEEDED' WHERE id=?", id);
    // A successful overall run may have skipped a recently scanned channel.
    assertThat(schedule.due(NOW.plusSeconds(60)).discovery()).isTrue();
    writer.update(
        "INSERT INTO app.discovery_channel_state(channel_id, uploads_id, last_scanned_at) VALUES (?, 'uploads', ?)",
        CHANNEL_ID,
        java.sql.Timestamp.from(NOW));
    assertThat(schedule.due(NOW.plusSeconds(60)).discovery()).isFalse();
    assertThat(schedule.due(NOW.plusSeconds(7200)).discovery()).isTrue();
    writer.update(
        "INSERT INTO app.collection_retry_request(id, kind, run_id, expected_attempt, created_at) VALUES(?, 'DISCOVERY', ?, 1, ?)",
        UUID.randomUUID(),
        id,
        java.sql.Timestamp.from(NOW));
    assertThat(schedule.due(NOW).retries()).isTrue();
  }

  @Test
  void discoveryWaitsUntilChannelsActuallyNeedScanningAndContinuesIncompletePages() {
    var owner =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    new ResourceDatabasePopulator(new ClassPathResource("discovery-grants.sql")).execute(owner);
    writer.execute("TRUNCATE app.discovery_run");
    var lastScan = NOW.plusSeconds(30);
    writer.update(
        "INSERT INTO app.discovery_channel_state(channel_id, uploads_id, last_scanned_at) VALUES (?, 'uploads', ?)",
        CHANNEL_ID,
        java.sql.Timestamp.from(lastScan));
    var schedule = new CollectionScheduleState(source, true, false);
    // A prior run's scan can finish slightly after :17. Do not mark the next window
    // complete while the collector would skip it due to the two-hour freshness guard.
    assertThat(schedule.due(NOW.plusSeconds(7200)).discovery()).isFalse();
    assertThat(schedule.due(lastScan.plusSeconds(7200)).discovery()).isTrue();
    writer.update(
        "INSERT INTO app.discovery_run(id, mode, started_at, status) VALUES (?, 'NEW', ?, 'SUCCEEDED')",
        UUID.randomUUID(),
        java.sql.Timestamp.from(NOW.plusSeconds(7200)));
    assertThat(schedule.due(lastScan.plusSeconds(7200)).discovery()).isTrue();
    writer.update(
        "UPDATE app.discovery_channel_state SET last_scanned_at=? WHERE channel_id=?",
        java.sql.Timestamp.from(lastScan.plusSeconds(7200)),
        CHANNEL_ID);
    assertThat(schedule.due(lastScan.plusSeconds(7200)).discovery()).isFalse();
    writer.update(
        "UPDATE app.discovery_channel_state SET in_progress=true WHERE channel_id=?", CHANNEL_ID);
    assertThat(schedule.due(lastScan.plusSeconds(7200)).discovery()).isTrue();
  }

  @Test
  void sharedExecutionKeepsWebPollingFromCollectingViewsBeforeTheyAreDue() {
    seed(1);
    success(List.of(1), 100);
    var execution =
        new CollectorExecution(
            settings(Duration.ofSeconds(30), 1),
            new org.springframework.mock.env.MockEnvironment());
    assertThat(
            execution.execute(
                source, new org.springframework.boot.DefaultApplicationArguments(), false))
        .isZero();
    server.verify(0, getRequestedFor(urlPathEqualTo("/videos")));
    assertThat(
            execution.execute(source, new org.springframework.boot.DefaultApplicationArguments()))
        .isZero();
    server.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }
}
