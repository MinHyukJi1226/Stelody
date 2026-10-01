package com.stelody.collector;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.config.DiscoveryOptions;
import com.stelody.collector.repository.DiscoveryRepository;
import com.stelody.collector.service.CollectionLock;
import com.stelody.collector.service.DiscoveryCollector;
import com.zaxxer.hikari.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class DiscoveryCollectorIntegrationTest {
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

  static final UUID CHANNEL = new UUID(0, 1);
  static final String YTCHANNEL = "UC" + "a".repeat(22), UPLOADS = "UU" + "a".repeat(22);
  static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
  @Autowired ObjectMapper mapper;
  JdbcTemplate writer, collector;
  HikariDataSource source;
  WireMockServer server;

  @BeforeEach
  void setup() {
    var owner =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    writer = new JdbcTemplate(owner);
    writer.execute(
        "TRUNCATE app.channel,app.song_entry,app.discovery_run,app.view_publication CASCADE");
    writer.execute("UPDATE app.collection_control SET owner_token=NULL");
    new ResourceDatabasePopulator(
            new ClassPathResource("collector-grants.sql"),
            new ClassPathResource("discovery-grants.sql"))
        .execute(owner);
    writer.update(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES(?,?,'test','GROUP',true)",
        CHANNEL,
        YTCHANNEL);
    var c = new HikariConfig();
    c.setJdbcUrl(postgres.getJdbcUrl());
    c.setUsername("stelody_collector");
    c.setPassword("test-collector");
    c.setMaximumPoolSize(2);
    c.setMinimumIdle(0);
    source = new HikariDataSource(c);
    collector = new JdbcTemplate(source);
    server = new WireMockServer(0);
    server.start();
    server.stubFor(
        get(urlPathEqualTo("/channels"))
            .willReturn(
                okJson(
                    mapper.writeValueAsString(
                        Map.of(
                            "kind",
                            "youtube#channelListResponse",
                            "items",
                            List.of(
                                Map.of(
                                    "id",
                                    YTCHANNEL,
                                    "contentDetails",
                                    Map.of("relatedPlaylists", Map.of("uploads", UPLOADS)))))))));
  }

  @AfterEach
  void stop() {
    server.stop();
    source.close();
  }

  String yt(int n) {
    return "YT%09d".formatted(n);
  }

  void page(String token, String next, int... numbers) {
    var items = new ArrayList<Object>();
    for (int n : numbers)
      items.add(
          Map.of(
              "snippet",
              Map.of(
                  "playlistId",
                  UPLOADS,
                  "channelId",
                  YTCHANNEL,
                  "resourceId",
                  Map.of("kind", "youtube#video", "videoId", yt(n))),
              "contentDetails",
              Map.of("videoId", yt(n))));
    var body = new HashMap<String, Object>();
    body.put("kind", "youtube#playlistItemListResponse");
    body.put("items", items);
    if (next != null) body.put("nextPageToken", next);
    server.stubFor(
        get(urlPathEqualTo("/playlistItems"))
            .withQueryParam("pageToken", token == null ? absent() : equalTo(token))
            .willReturn(okJson(mapper.writeValueAsString(body))));
  }

  void videos(int... numbers) {
    videosFor(YTCHANNEL, "PUBLIC", numbers);
  }

  void videosFor(String channel, String availability, int... numbers) {
    var items = new ArrayList<Object>();
    for (int n : numbers)
      items.add(
          Map.of(
              "id",
              yt(n),
              "snippet",
              Map.of(
                  "channelId",
                  channel,
                  "title",
                  "Test Cover " + n,
                  "publishedAt",
                  "2026-09-01T00:00:00Z"),
              "status",
              Map.of("privacyStatus", availability.toLowerCase(), "embeddable", true),
              "contentDetails",
              Map.of("duration", "PT3M")));
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .withQueryParam(
                "id", equalTo(String.join(",", Arrays.stream(numbers).mapToObj(this::yt).toList())))
            .willReturn(
                okJson(
                    mapper.writeValueAsString(
                        Map.of("kind", "youtube#videoListResponse", "items", items)))));
  }

  CollectorSettings settings() {
    return new CollectorSettings(
        true,
        postgres.getJdbcUrl(),
        "stelody_collector",
        "test-collector",
        "test-key",
        URI.create(server.baseUrl()),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(20),
        3,
        Duration.ZERO);
  }

  com.stelody.collector.service.VideoCollector.Result run(
      Instant now, boolean backfill, int pages) {
    return new DiscoveryCollector(
            source,
            settings(),
            new DiscoveryOptions(true, backfill ? CHANNEL : null, backfill, pages),
            Clock.fixed(now, ZoneOffset.UTC),
            d -> {})
        .collect();
  }

  long count(String table) {
    return writer.queryForObject("SELECT count(*) FROM app." + table, Long.class);
  }

  @Test
  void initialScanStoresLatestPageOnlyAndNoSongsOrParticipants() {
    page(null, "older", 3, 2);
    videos(3, 2);
    var result = run(NOW, false, 3);
    assertThat(result.status()).as(result.toString()).isEqualTo("SUCCEEDED");
    assertThat(count("review_item")).isEqualTo(2);
    assertThat(count("song_entry")).isZero();
    assertThat(count("song_member")).isZero();
    assertThat(
            writer.queryForObject(
                "SELECT backfill_token FROM app.discovery_channel_state", String.class))
        .isEqualTo("older");
    server.verify(1, getRequestedFor(urlPathEqualTo("/playlistItems")));
    assertThat(writer.queryForList("SELECT disposition FROM app.review_item", String.class))
        .containsOnly("REVIEW");
  }

  void first() {
    page(null, "history", 1);
    videos(1);
    var result = run(NOW, false, 1);
    assertThat(result.status()).as(result.toString()).isEqualTo("SUCCEEDED");
    server.resetRequests();
  }

  @Test
  void partialScanResumesUntilPreviousBoundaryAndKeepsFirstHead() {
    first();
    page(null, "continue", 4, 3);
    videos(4, 3);
    assertThat(run(NOW.plusSeconds(7200), false, 1).status()).isEqualTo("SUCCEEDED");
    assertThat(
            writer.queryForObject(
                "SELECT head_video_id FROM app.discovery_channel_state", String.class))
        .isEqualTo(yt(1));
    page("continue", null, 2, 1);
    videos(2);
    assertThat(run(NOW.plusSeconds(7300), false, 1).status()).isEqualTo("SUCCEEDED");
    assertThat(
            writer.queryForObject(
                "SELECT head_video_id FROM app.discovery_channel_state", String.class))
        .isEqualTo(yt(4));
    assertThat(count("review_item")).isEqualTo(4);
    server.verify(
        1, getRequestedFor(urlPathEqualTo("/playlistItems")).withQueryParam("pageToken", absent()));
    server.verify(
        1,
        getRequestedFor(urlPathEqualTo("/playlistItems"))
            .withQueryParam("pageToken", equalTo("continue")));
    assertThat(
            writer.queryForObject(
                "SELECT backfill_token FROM app.discovery_channel_state", String.class))
        .isEqualTo("history");
  }

  @Test
  void quotaFailureRetainsCheckpointAndNextRunRetriesOnlyFailedPage() {
    first();
    page(null, "continue", 3);
    videos(3);
    run(NOW.plusSeconds(7200), false, 1);
    server.stubFor(
        get(urlPathEqualTo("/playlistItems"))
            .withQueryParam("pageToken", equalTo("continue"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}")));
    assertThat(run(NOW.plusSeconds(7300), false, 1).status()).isEqualTo("QUOTA_EXHAUSTED");
    assertThat(
            writer.queryForObject(
                "SELECT page_token FROM app.discovery_channel_state", String.class))
        .isEqualTo("continue");
    page("continue", null, 2, 1);
    videos(2);
    assertThat(run(NOW.plusSeconds(7400), false, 1).status()).isEqualTo("SUCCEEDED");
    assertThat(count("review_item")).isEqualTo(3);
  }

  @Test
  void ignoredAndRegisteredIdsAreSkippedAndNeverRecreated() {
    first();
    writer.execute(
        "UPDATE app.review_item SET review_status='IGNORED',review_note='manual ignore'");
    writer.execute(
        "INSERT INTO app.song_entry(id,title,search_title,song_type) VALUES('00000000-0000-0000-0000-000000000002','manual','manual','COVER')");
    writer.update(
        "INSERT INTO app.video(id,song_id,youtube_id,video_kind,availability,source_title) VALUES('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002',?,'OFFICIAL_COVER','PUBLIC','manual')",
        yt(2));
    page("history", null, 2, 1);
    assertThat(run(NOW.plusSeconds(7200), true, 1).status()).isEqualTo("SUCCEEDED");
    server.verify(0, getRequestedFor(urlPathEqualTo("/videos")));
    assertThat(count("review_item")).isEqualTo(1);
    assertThat(writer.queryForObject("SELECT review_note FROM app.review_item", String.class))
        .isEqualTo("manual ignore");
  }

  @Test
  void backfillContinuesWithoutChangingRecentBoundary() {
    first();
    page("history", "history2", 2);
    videos(2);
    run(NOW.plusSeconds(7200), true, 1);
    assertThat(
            writer.queryForObject(
                "SELECT backfill_token FROM app.discovery_channel_state", String.class))
        .isEqualTo("history2");
    page("history2", null, 3);
    videos(3);
    run(NOW.plusSeconds(7300), true, 1);
    assertThat(count("review_item")).isEqualTo(3);
    assertThat(
            writer.queryForObject(
                "SELECT head_video_id FROM app.discovery_channel_state", String.class))
        .isEqualTo(yt(1));
    assertThat(
            writer.queryForObject(
                "SELECT backfill_complete FROM app.discovery_channel_state", Boolean.class))
        .isTrue();
  }

  @Test
  void wrongVideoChannelRollsBackWholePage() {
    page(null, null, 1);
    videosFor("UC" + "b".repeat(22), "PUBLIC", 1);
    assertThat(run(NOW, false, 1).code()).isEqualTo("CHANNEL_MISMATCH");
    assertThat(count("review_item")).isZero();
    assertThat(
            writer.queryForObject(
                "SELECT head_video_id FROM app.discovery_channel_state", String.class))
        .isNull();
  }

  @Test
  void invalidPageTokenRestartsSafelyWithoutLosingBoundary() {
    first();
    page(null, "expired", 3);
    videos(3);
    run(NOW.plusSeconds(7200), false, 1);
    server.stubFor(
        get(urlPathEqualTo("/playlistItems"))
            .withQueryParam("pageToken", equalTo("expired"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"invalidPageToken\"}]}}")));
    assertThat(run(NOW.plusSeconds(7300), false, 1).code()).isEqualTo("INVALID_PAGE_TOKEN");
    assertThat(
            writer.queryForObject(
                "SELECT page_token FROM app.discovery_channel_state", String.class))
        .isNull();
    assertThat(
            writer.queryForObject(
                "SELECT head_video_id FROM app.discovery_channel_state", String.class))
        .isEqualTo(yt(1));
    page(null, null, 3, 2, 1);
    videos(3, 2);
    assertThat(run(NOW.plusSeconds(7400), false, 1).status()).isEqualTo("SUCCEEDED");
    assertThat(count("review_item")).isEqualTo(3);
  }

  @Test
  void deferredVideoIsRecheckedAfterPublicationWithoutRediscovery() {
    page(null, null, 1);
    videosFor(YTCHANNEL, "PRIVATE", 1);
    run(NOW, false, 1);
    videos(1);
    assertThat(run(NOW.plusSeconds(7201), false, 1).status()).isEqualTo("SUCCEEDED");
    assertThat(writer.queryForObject("SELECT availability FROM app.review_item", String.class))
        .isEqualTo("PUBLIC");
    assertThat(writer.queryForObject("SELECT disposition FROM app.review_item", String.class))
        .isEqualTo("REVIEW");
    assertThat(count("song_entry")).isZero();
  }

  @Test
  void disabledAndExternalChannelsCannotBeScanned() {
    writer.execute("UPDATE app.channel SET channel_type='EXTERNAL'");
    assertThat(run(NOW, true, 1).code()).isEqualTo("CHANNEL_NOT_ALLOWED");
    server.verify(0, getRequestedFor(urlPathEqualTo("/channels")));
  }

  @Test
  void sharedLockAndFencingPreventStaleDiscoveryWrites() throws Exception {
    try (var lock = CollectionLock.acquire(source)) {
      assertThat(run(NOW, false, 1).status()).isEqualTo("SKIPPED_LOCKED");
    }
    var repository = new DiscoveryRepository(source);
    UUID old = UUID.randomUUID(), current = UUID.randomUUID();
    UUID id = repository.begin(old, null, false, NOW);
    repository.begin(current, null, false, NOW);
    assertThatThrownBy(() -> repository.finish(old, id, "SUCCEEDED", null, NOW))
        .hasMessage("LOCK_LOST");
  }

  @Test
  void timeoutKeepsCompletedPageForNextInvocation() {
    first();
    page(null, "continue", 3, 2);
    videos(3, 2);
    server.stubFor(
        get(urlPathEqualTo("/playlistItems"))
            .withQueryParam("pageToken", equalTo("continue"))
            .willReturn(aResponse().withStatus(429)));
    var nanos = new java.util.concurrent.atomic.AtomicLong();
    var result =
        new DiscoveryCollector(
                source,
                settings(),
                new DiscoveryOptions(true, null, false, 3),
                Clock.fixed(NOW.plusSeconds(7201), ZoneOffset.UTC),
                d -> nanos.set(Duration.ofSeconds(21).toNanos()))
            .collect(
                new com.stelody.collector.domain.CollectionBudget(
                    Duration.ofSeconds(20), nanos::get));
    assertThat(result.status()).isEqualTo("TIMED_OUT");
    assertThat(
            writer.queryForObject(
                "SELECT page_token FROM app.discovery_channel_state", String.class))
        .isEqualTo("continue");
    assertThat(
            writer.queryForObject(
                "SELECT head_video_id FROM app.discovery_channel_state", String.class))
        .isEqualTo(yt(1));
    page("continue", null, 1);
    assertThat(run(NOW.plusSeconds(7202), false, 3).status()).isEqualTo("SUCCEEDED");
    assertThat(
            writer.queryForObject(
                "SELECT head_video_id FROM app.discovery_channel_state", String.class))
        .isEqualTo(yt(3));
  }

  @Test
  void channelDisabledDuringHttpRequestPreventsPageCommit() {
    page(null, "history", 1);
    server.stubFor(get(urlPathEqualTo("/videos")).willReturn(aResponse().withStatus(429)));
    var result =
        new DiscoveryCollector(
                source,
                settings(),
                new DiscoveryOptions(true, null, false, 1),
                Clock.fixed(NOW, ZoneOffset.UTC),
                d -> {
                  writer.execute("UPDATE app.channel SET collection_enabled=false");
                  videos(1);
                })
            .collect();
    assertThat(result.code()).isEqualTo("CHANNEL_NOT_ALLOWED");
    assertThat(writer.queryForObject("SELECT count(*) FROM app.review_item", Integer.class))
        .isZero();
    assertThat(
            writer.queryForObject(
                "SELECT head_video_id FROM app.discovery_channel_state", String.class))
        .isNull();
  }

  @Test
  void collectorCannotChangeReviewDecisionsOrReadAuditAndExpirationKeepsIgnoreIdentity() {
    first();
    writer.execute("UPDATE app.review_item SET review_status='IGNORED',review_note='manual'");
    assertThatThrownBy(
            () -> collector.execute("UPDATE app.review_item SET review_status='PENDING'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> collector.queryForList("SELECT review_note FROM app.review_item"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> collector.execute("INSERT INTO app.review_item(review_status) VALUES('IGNORED')"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasRootCauseMessage("ERROR: permission denied for table review_item");
    assertThatThrownBy(
            () ->
                collector.execute(
                    "INSERT INTO app.song_entry(id,title,search_title,song_type) VALUES(gen_random_uuid(),'x','x','COVER')"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    UUID token = UUID.randomUUID();
    var repository = new DiscoveryRepository(source);
    repository.begin(token, null, false, NOW.plusSeconds(2592001));
    repository.cleanup(token, NOW.plusSeconds(2592001));
    assertThat(writer.queryForObject("SELECT source_title FROM app.review_item", String.class))
        .isNull();
    assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
        .isEqualTo("IGNORED");
    assertThat(repository.needed(List.of(yt(1)))).isEmpty();
  }
}
