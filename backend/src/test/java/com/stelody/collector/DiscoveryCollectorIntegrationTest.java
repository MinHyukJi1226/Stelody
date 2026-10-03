package com.stelody.collector;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.config.DiscoveryOptions;
import com.stelody.collector.domain.RuleConfiguration;
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
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
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
  @Autowired com.stelody.admin.service.CatalogManagementService catalogManagement;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  JdbcTemplate writer, collector;
  HikariDataSource source;
  WireMockServer server;

  @BeforeEach
  void setup() {
    var owner =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    writer = new JdbcTemplate(owner);
    writer.execute(
        "TRUNCATE app.member,app.channel,app.song_entry,app.discovery_run,app.view_publication CASCADE");
    writer.execute("UPDATE app.collection_control SET owner_token=NULL");
    writer.execute(
        "INSERT INTO app.catalog_state(singleton) VALUES(true) ON CONFLICT(singleton) DO NOTHING");
    writer.execute("UPDATE app.cover_publication_control SET enabled=false,version=0");
    writer.update(
        "UPDATE app.collection_rule SET version=0,configuration=CAST(? AS jsonb)",
        mapper.writeValueAsString(RuleConfiguration.defaults()));
    new ResourceDatabasePopulator(
            new ClassPathResource("collector-grants.sql"),
            new ClassPathResource("discovery-grants.sql"),
            new ClassPathResource("collection-rule-grants.sql"),
            new ClassPathResource("cover-publication-grants.sql"))
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
  void collectorFreezesRuleVersionUntilNextRun() {
    var exclude =
        new RuleConfiguration(
            List.of(),
            List.of(),
            List.of(new RuleConfiguration.Marker("cover", RuleConfiguration.Match.WORD)));
    writer.update(
        "UPDATE app.collection_rule SET configuration=CAST(? AS jsonb)",
        mapper.writeValueAsString(exclude));
    var changed = new java.util.concurrent.atomic.AtomicBoolean();
    server.addMockServiceRequestListener(
        (request, response) -> {
          if (request.getUrl().startsWith("/videos") && changed.compareAndSet(false, true))
            writer.update(
                "UPDATE app.collection_rule SET version=1,configuration=CAST(? AS jsonb)",
                mapper.writeValueAsString(new RuleConfiguration(List.of(), List.of(), List.of())));
        });
    page(null, "history", 1);
    videos(1);
    assertThat(run(NOW, false, 1).status()).isEqualTo("SUCCEEDED");
    assertThat(writer.queryForObject("SELECT rule_version FROM app.review_item", String.class))
        .isEqualTo("title-v2:0");
    assertThat(writer.queryForObject("SELECT disposition FROM app.review_item", String.class))
        .isEqualTo("EXCLUDED");
    page(null, null, 2, 1);
    videos(2);
    assertThat(run(NOW.plusSeconds(7200), false, 1).status()).isEqualTo("SUCCEEDED");
    assertThat(
            writer.queryForObject(
                "SELECT suggested_type FROM app.review_item WHERE youtube_id=?",
                String.class,
                yt(2)))
        .isEqualTo("UNKNOWN");
    assertThat(
            writer.queryForObject(
                "SELECT rule_version FROM app.review_item WHERE youtube_id=?", String.class, yt(2)))
        .isEqualTo("title-v2:1");
  }

  @Test
  void missingRuleGrantFailsBeforeRemoteFetchAndDoesNotFallbackToDefaults() {
    writer.execute("REVOKE SELECT ON app.collection_rule FROM stelody_collector");
    assertThat(run(NOW, false, 1).status()).isEqualTo("FAILED");
    assertThat(count("review_item")).isZero();
    server.verify(0, getRequestedFor(urlPathEqualTo("/channels")));
    page(null, null, 1);
    videos(1);
    var result =
        new DiscoveryCollector(
                source,
                settings(),
                new DiscoveryOptions(false, null, false, 1),
                Clock.fixed(NOW, ZoneOffset.UTC),
                d -> {})
            .collect();
    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(writer.queryForObject("SELECT decision_reason FROM app.review_item", String.class))
        .isEqualTo("CLASSIFICATION_DISABLED");
  }

  @Test
  void initialScanStoresLatestPageOnlyAndNoSongsOrParticipants() {
    page(null, "older", 3, 2);
    videos(3, 2);
    var result = run(NOW, false, 3);
    assertThat(result.status()).as(result.toString()).isEqualTo("SUCCEEDED");
    assertThat(count("review_item")).isEqualTo(2);
    assertThat(
            writer.queryForObject(
                "SELECT rule_version FROM app.discovery_run WHERE id=?",
                String.class,
                result.runId()))
        .isEqualTo("title-v2:0");
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
    assertThatThrownBy(() -> collector.queryForList("SELECT * FROM app.admin_audit"))
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

  void enableAuto() {
    writer.execute("UPDATE app.cover_publication_control SET enabled=true");
    writer.execute(
        "INSERT INTO app.member(id,name,search_name,activity_status) VALUES('00000000-0000-0000-0000-000000000010','아오쿠모 린','아오쿠모 린','GRADUATED')");
    writer.execute(
        "INSERT INTO app.member_alias VALUES('00000000-0000-0000-0000-000000000010','Aokumo Rin','aokumo rin')");
    writer.update(
        "UPDATE app.channel SET channel_type='MEMBER',member_id='00000000-0000-0000-0000-000000000010' WHERE id=?",
        CHANNEL);
  }

  void clearCovers(String title, String live, String duration, String published, int... numbers) {
    var items = new ArrayList<Object>();
    for (int n : numbers)
      items.add(
          Map.of(
              "id",
              yt(n),
              "snippet",
              Map.of(
                  "channelId",
                  YTCHANNEL,
                  "title",
                  title,
                  "publishedAt",
                  published,
                  "liveBroadcastContent",
                  live),
              "status",
              Map.of("privacyStatus", "public", "embeddable", false),
              "contentDetails",
              Map.of("duration", duration)));
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .willReturn(
                okJson(
                    mapper.writeValueAsString(
                        Map.of("kind", "youtube#videoListResponse", "items", items)))));
  }

  void clearCovers(int... numbers) {
    clearCovers(
        "노래 / 아오쿠모 린 (Aokumo Rin) Cover", "none", "PT3M21S", "2026-09-01T00:00:00Z", numbers);
  }

  com.stelody.collector.service.VideoCollector.Result autoRun(
      Instant now, boolean policy, boolean classification, boolean backfill) {
    return new DiscoveryCollector(
            source,
            settings(),
            new DiscoveryOptions(classification, backfill ? CHANNEL : null, backfill, 1, policy),
            Clock.fixed(now, ZoneOffset.UTC),
            d -> {})
        .collect();
  }

  @Test
  void explicitSoloCreatesPublishedCoverRepresentativeConfirmedMemberAndRegistrationHistory()
      throws Exception {
    enableAuto();
    page(null, "history", 1, 2);
    clearCovers(1, 2);
    var result = autoRun(NOW, true, true, false);
    assertThat(result.status()).as(result.toString()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isEqualTo(2);
    assertThat(writer.queryForList("SELECT visibility FROM app.song_entry", String.class))
        .containsOnly("PUBLISHED");
    assertThat(writer.queryForList("SELECT search_visibility FROM app.song_entry", String.class))
        .containsOnly("UNCHECKED");
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.song_entry WHERE representative_video_id IS NOT NULL AND work_id IS NULL AND NOT is_special_event",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.song_member WHERE confirmed AND member_id='00000000-0000-0000-0000-000000000010'",
                Integer.class))
        .isEqualTo(2);
    assertThat(writer.queryForList("SELECT review_status FROM app.review_item", String.class))
        .containsOnly("REGISTERED");
    assertThat(
            writer.queryForList(
                "SELECT rule_version FROM app.cover_auto_registration", String.class))
        .containsOnly("title-v2:0/solo-credit-v1");
    assertThat(writer.queryForList("SELECT embeddable FROM app.video", Boolean.class))
        .containsOnly(false);
    assertThat(count("work_artist")).isZero();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/songs"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.items.length()")
                .value(2))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.items[0].participants[0].activityStatus")
                .value("GRADUATED"));
    page("history", null, 1, 2);
    server.resetRequests();
    assertThat(autoRun(NOW.plusSeconds(7201), true, true, true).status()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isEqualTo(2);
    assertThat(count("cover_auto_registration")).isEqualTo(2);
    server.verify(0, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void stopAndPolicyAndClassificationGatesAllKeepDiscoveryWorking() {
    enableAuto();
    page(null, null, 1);
    clearCovers(1);
    assertThat(autoRun(NOW, false, true, false).status()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isZero();
    writer.execute("UPDATE app.cover_publication_control SET enabled=false");
    assertThat(autoRun(NOW.plusSeconds(7201), true, true, false).status()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isZero();
    writer.execute("UPDATE app.cover_publication_control SET enabled=true");
    page(null, null, 2, 1);
    clearCovers(2);
    assertThat(autoRun(NOW.plusSeconds(14402), true, false, false).status()).isEqualTo("SUCCEEDED");
    assertThat(count("review_item")).isEqualTo(2);
    assertThat(count("song_entry")).isZero();
  }

  @Test
  void activationReobservesExistingCandidateBehindDiscoveryBoundary() {
    enableAuto();
    page(null, null, 1);
    clearCovers(1);
    autoRun(NOW, false, false, false);
    server.resetRequests();
    assertThat(autoRun(NOW.plusSeconds(7201), true, true, false).status()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isEqualTo(1);
    server.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void ownerAloneOrMultipleMembersOrExternalCreditsOrShortUploadsRemainReview() {
    enableAuto();
    page(null, null, 1);
    for (String title :
        List.of(
            "Song Cover",
            "Song Live / 아오쿠모 린 Cover",
            "Song feat. Guest / 아오쿠모 린 Cover",
            "Song / Guest & 아오쿠모 린 Cover")) {
      writer.execute("TRUNCATE app.discovery_channel_state,app.review_item CASCADE");
      clearCovers(title, "none", "PT4M", "2026-09-01T00:00:00Z", 1);
      assertThat(autoRun(NOW, true, true, false).status()).isEqualTo("SUCCEEDED");
      assertThat(count("song_entry")).isZero();
      assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
          .isEqualTo("PENDING");
    }
    writer.execute("TRUNCATE app.discovery_channel_state,app.review_item CASCADE");
    clearCovers("Song / 아오쿠모 린 Cover", "none", "PT30S", "2026-09-01T00:00:00Z", 1);
    autoRun(NOW, true, true, false);
    assertThat(writer.queryForObject("SELECT decision_reason FROM app.review_item", String.class))
        .isEqualTo("FULL_LENGTH_UNCONFIRMED");
    assertThat(writer.queryForObject("SELECT disposition FROM app.review_item", String.class))
        .isEqualTo("REVIEW");
    assertThat(count("song_entry")).isZero();
  }

  @Test
  void futureAndUpcomingPublicationWaitAndThenRegisterOnRecheck() {
    enableAuto();
    page(null, null, 1);
    clearCovers("Song / 아오쿠모 린 Cover", "none", "PT4M", NOW.plusSeconds(3600).toString(), 1);
    autoRun(NOW, true, true, false);
    assertThat(writer.queryForObject("SELECT disposition FROM app.review_item", String.class))
        .isEqualTo("DEFERRED");
    assertThat(count("song_entry")).isZero();
    assertThat(autoRun(NOW.plusSeconds(7201), true, true, false).status()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isEqualTo(1);
  }

  @Test
  void explicitSoloOnGroupChannelWorksButUnmappedPersonalChannelRequiresReview() {
    enableAuto();
    writer.execute("UPDATE app.channel SET member_id=NULL");
    page(null, null, 1);
    clearCovers(1);
    autoRun(NOW, true, true, false);
    assertThat(count("song_entry")).isZero();
    assertThat(writer.queryForObject("SELECT decision_reason FROM app.review_item", String.class))
        .isEqualTo("PARTICIPANT_CONFIGURATION_CHANGED");
    writer.execute("UPDATE app.channel SET channel_type='GROUP'");
    assertThat(autoRun(NOW.plusSeconds(7201), true, true, false).status()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isEqualTo(1);
  }

  @Test
  void ignoredCandidateNeverAutoPublishesEvenAfterActivation() {
    enableAuto();
    page(null, null, 1);
    clearCovers(1);
    autoRun(NOW, false, true, false);
    writer.execute("UPDATE app.review_item SET review_status='IGNORED',review_note='keep ignored'");
    assertThat(autoRun(NOW.plusSeconds(7201), true, true, false).status()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isZero();
    assertThat(writer.queryForObject("SELECT review_note FROM app.review_item", String.class))
        .isEqualTo("keep ignored");
  }

  @Test
  void registrationFailureRollsBackWholePageAndCheckpointThenRetrySucceeds() {
    enableAuto();
    page(null, null, 1, 2);
    clearCovers(1, 2);
    writer.execute(
        "CREATE FUNCTION app.fail_auto_registration() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture failure'; END $$");
    writer.execute(
        "CREATE TRIGGER fail_auto_registration BEFORE INSERT ON app.cover_auto_registration FOR EACH ROW EXECUTE FUNCTION app.fail_auto_registration()");
    try {
      assertThat(autoRun(NOW, true, true, false).code()).isEqualTo("DATABASE_ERROR");
      for (String table :
          List.of("review_item", "video", "song_entry", "song_member", "cover_auto_registration"))
        assertThat(count(table)).isZero();
      assertThat(
              writer.queryForObject(
                  "SELECT head_video_id FROM app.discovery_channel_state", String.class))
          .isNull();
    } finally {
      writer.execute("DROP TRIGGER fail_auto_registration ON app.cover_auto_registration");
      writer.execute("DROP FUNCTION app.fail_auto_registration()");
    }
    assertThat(autoRun(NOW.plusSeconds(1), true, true, false).status()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isEqualTo(2);
  }

  @Test
  void refreshPreservesManualTitleWorkSpecialLabelParticipantsAndHiddenVisibility() {
    enableAuto();
    page(null, null, 1);
    clearCovers(1);
    autoRun(NOW, true, true, false);
    writer.execute(
        "INSERT INTO app.musical_work(id,title,search_title) VALUES('00000000-0000-0000-0000-000000000020','manual work','manual work')");
    writer.execute(
        "UPDATE app.song_entry SET title='manual title',search_title='manual title',visibility='HIDDEN',work_id='00000000-0000-0000-0000-000000000020',is_special_event=true,special_event_label='manual label',version=9");
    writer.execute("UPDATE app.video SET published_at='2025-01-01T00:00:00Z'");
    videos(1);
    var refresh =
        new com.stelody.collector.service.VideoCollector(
                source, settings(), Clock.fixed(NOW.plusSeconds(7201), ZoneOffset.UTC), d -> {})
            .collect();
    assertThat(refresh.status()).as(refresh.toString()).isEqualTo("SUCCEEDED");
    assertThat(
            writer.queryForObject(
                "SELECT title||'/'||visibility||'/'||special_event_label FROM app.song_entry",
                String.class))
        .isEqualTo("manual title/HIDDEN/manual label");
    assertThat(writer.queryForObject("SELECT version FROM app.song_entry", Long.class))
        .isEqualTo(9);
    assertThat(writer.queryForObject("SELECT work_id FROM app.song_entry", UUID.class))
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000020"));
    assertThat(writer.queryForObject("SELECT source_title FROM app.video", String.class))
        .isEqualTo("Test Cover 1");
    assertThat(writer.queryForObject("SELECT video_kind FROM app.video", String.class))
        .isEqualTo("OFFICIAL_COVER");
    assertThat(count("song_member")).isEqualTo(1);
    assertThat(count("cover_auto_registration")).isEqualTo(1);
  }

  @Test
  void publicationFunctionAndNewGrantDoNotExposePrivateDataOrBroadCatalogWrites() {
    for (String table :
        List.of(
            "app_user",
            "favorite",
            "playlist",
            "playlist_item",
            "catalog_audit",
            "cover_auto_registration"))
      assertThatThrownBy(() -> collector.queryForList("SELECT * FROM app." + table))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> collector.execute("UPDATE app.song_entry SET visibility='PUBLISHED'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> collector.execute("UPDATE app.cover_publication_control SET enabled=true"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    var web =
        new JdbcTemplate(
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_app", "test-runtime"));
    assertThatThrownBy(
            () ->
                web.queryForList(
                    "SELECT app.publish_discovered_cover(NULL,now(),NULL,0,'x',true,'solo-credit-v1',NULL)"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void stopDuringRemoteRequestPreventsPublicationButKeepsCandidateAndCheckpoint() {
    enableAuto();
    page(null, null, 1);
    server.stubFor(get(urlPathEqualTo("/videos")).willReturn(aResponse().withStatus(429)));
    var result =
        new DiscoveryCollector(
                source,
                settings(),
                new DiscoveryOptions(true, null, false, 1, true),
                Clock.fixed(NOW, ZoneOffset.UTC),
                d -> {
                  writer.execute(
                      "UPDATE app.cover_publication_control SET enabled=false,version=version+1");
                  clearCovers(1);
                })
            .collect();
    assertThat(result.status()).as(result.toString()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isZero();
    assertThat(count("review_item")).isEqualTo(1);
    assertThat(
            writer.queryForObject(
                "SELECT head_video_id FROM app.discovery_channel_state", String.class))
        .isEqualTo(yt(1));
  }

  @Test
  void missingPublicationGrantFailsBeforeApiCallsAndPolicyDisabledStillWorks() {
    enableAuto();
    page(null, null, 1);
    clearCovers(1);
    writer.execute(
        "REVOKE EXECUTE ON FUNCTION app.publish_discovered_cover(UUID,TIMESTAMPTZ,UUID,BIGINT,TEXT,BOOLEAN,TEXT,UUID) FROM stelody_collector");
    assertThat(autoRun(NOW, true, true, false).code())
        .isEqualTo("COVER_PUBLICATION_GRANTS_MISSING");
    server.verify(0, getRequestedFor(urlPathEqualTo("/channels")));
    assertThat(autoRun(NOW, false, true, false).status()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isZero();
  }

  @Test
  void excludedAndUpcomingAndLiveCandidatesNeverPublish() {
    enableAuto();
    page(null, null, 1);
    for (String live : List.of("upcoming", "live", "unknown")) {
      writer.execute("TRUNCATE app.discovery_channel_state,app.review_item CASCADE");
      clearCovers("Song / 아오쿠모 린 Cover", live, "PT4M", "2026-09-01T00:00:00Z", 1);
      assertThat(autoRun(NOW, true, true, false).status()).isEqualTo("SUCCEEDED");
      assertThat(count("song_entry")).isZero();
    }
    writer.execute("TRUNCATE app.discovery_channel_state,app.review_item CASCADE");
    clearCovers("Song / 아오쿠모 린 Cover #Shorts", "none", "PT4M", "2026-09-01T00:00:00Z", 1);
    autoRun(NOW, true, true, false);
    assertThat(writer.queryForObject("SELECT disposition FROM app.review_item", String.class))
        .isEqualTo("EXCLUDED");
    assertThat(count("song_entry")).isZero();
  }

  @Test
  void concurrentDuplicatePublicationProducesOneSongAndCannotUnhideLater() throws Exception {
    enableAuto();
    page(null, null, 1);
    clearCovers(1);
    autoRun(NOW, false, true, false);
    writer.execute("UPDATE app.review_item SET decision_reason='EXPLICIT_SOLO_COVER_CREDIT'");
    UUID token = UUID.randomUUID();
    new DiscoveryRepository(source).begin(token, null, false, NOW);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<UUID> task =
          () -> {
            start.await();
            return collector.queryForObject(
                "SELECT app.publish_discovered_cover(id,source_observed_at,'00000000-0000-0000-0000-000000000010',0,'song',true,'solo-credit-v1',?) FROM app.review_item",
                UUID.class,
                token);
          };
      var one = pool.submit(task);
      var two = pool.submit(task);
      start.countDown();
      UUID a = one.get(5, java.util.concurrent.TimeUnit.SECONDS),
          b = two.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(a == null).isNotEqualTo(b == null);
      assertThat(count("song_entry")).isEqualTo(1);
      assertThat(count("cover_auto_registration")).isEqualTo(1);
      writer.execute("UPDATE app.song_entry SET visibility='HIDDEN',title='manual'");
      assertThat(task.call()).isNull();
      assertThat(
              writer.queryForObject(
                  "SELECT visibility||'/'||title FROM app.song_entry", String.class))
          .isEqualTo("HIDDEN/manual");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void manualAndAutomaticRegistrationOfSameCandidateUseConsistentLocks() throws Exception {
    enableAuto();
    page(null, null, 1);
    clearCovers(1);
    autoRun(NOW, false, true, false);
    writer.execute("UPDATE app.review_item SET decision_reason='EXPLICIT_SOLO_COVER_CREDIT'");
    UUID review = writer.queryForObject("SELECT id FROM app.review_item", UUID.class),
        target = UUID.randomUUID(),
        actor = UUID.randomUUID();
    writer.update(
        "INSERT INTO app.app_user(id,google_subject,email,role) VALUES(?,?,'race@example.invalid','ADMIN')",
        actor,
        actor.toString());
    writer.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type) VALUES(?,'manual draft','manual draft','COVER')",
        target);
    UUID token = UUID.randomUUID();
    new DiscoveryRepository(source).begin(token, null, false, NOW);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      var automatic =
          pool.submit(
              () -> {
                start.await();
                return collector.queryForObject(
                    "SELECT app.publish_discovered_cover(id,source_observed_at,'00000000-0000-0000-0000-000000000010',0,'song',true,'solo-credit-v1',?) FROM app.review_item",
                    UUID.class,
                    token);
              });
      var manual =
          pool.submit(
              () -> {
                start.await();
                try {
                  catalogManagement.register(
                      review,
                      new com.stelody.admin.dto.CatalogAdminDtos.Registration(
                          0L,
                          target,
                          0L,
                          com.stelody.admin.dto.CatalogAdminDtos.VideoKind.OFFICIAL_COVER,
                          "manual race"),
                      actor);
                  return true;
                } catch (com.stelody.admin.web.AdminCatalogException e) {
                  assertThat(e.status()).isEqualTo(409);
                  return false;
                }
              });
      start.countDown();
      var autoSong = automatic.get(5, java.util.concurrent.TimeUnit.SECONDS);
      boolean manualWon = manual.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(manualWon).isEqualTo(autoSong == null);
      assertThat(count("video")).isEqualTo(1);
      assertThat(count("cover_auto_registration")).isEqualTo(manualWon ? 0 : 1);
      assertThat(count("song_entry")).isEqualTo(manualWon ? 1 : 2);
      assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
          .isEqualTo("REGISTERED");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void discoveryRetryPreservesAutoPublicationPolicyAndCreatesCover() {
    enableAuto();
    page(null, null, 1);
    clearCovers(1);
    writer.execute(
        "REVOKE EXECUTE ON FUNCTION app.publish_discovered_cover(UUID,TIMESTAMPTZ,UUID,BIGINT,TEXT,BOOLEAN,TEXT,UUID) FROM stelody_collector");
    var failed = autoRun(NOW, true, true, false);
    assertThat(failed.code()).isEqualTo("COVER_PUBLICATION_GRANTS_MISSING");
    new ResourceDatabasePopulator(
            new ClassPathResource("cover-publication-grants.sql"),
            new ClassPathResource("collection-operations-grants.sql"))
        .execute(writer.getDataSource());
    writer.execute("TRUNCATE app.collection_retry_request");
    writer.update(
        "INSERT INTO app.collection_retry_request(id,kind,run_id,expected_attempt,created_at) VALUES(?,'DISCOVERY',?,1,?)",
        UUID.randomUUID(),
        failed.runId(),
        java.sql.Timestamp.from(NOW));
    var retried =
        new com.stelody.collector.service.CollectionRetryWorker(
                source, settings(), Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), d -> {})
            .collect(
                new com.stelody.collector.domain.CollectionBudget(Duration.ofMinutes(1)),
                true,
                true,
                true)
            .orElseThrow();
    assertThat(retried.status()).as(retried.toString()).isEqualTo("SUCCEEDED");
    assertThat(count("song_entry")).isEqualTo(1);
    assertThat(
            writer.queryForObject("SELECT status FROM app.collection_retry_request", String.class))
        .isEqualTo("SUCCEEDED");
  }
}
