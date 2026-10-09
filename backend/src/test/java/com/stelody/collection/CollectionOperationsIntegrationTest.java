package com.stelody.collection;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.auth.domain.SessionUser;
import com.stelody.collection.dto.CollectionDtos;
import com.stelody.collection.repository.CollectionQueries;
import com.stelody.collection.service.CollectionOperations;
import com.stelody.collection.web.CollectionException;
import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.service.*;
import com.zaxxer.hikari.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest(properties = "stelody.collection.retry-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class CollectionOperationsIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", () -> "stelody_app");
    r.add("spring.datasource.password", () -> "test-runtime");
    r.add("spring.flyway.user", () -> "stelody_migrator");
    r.add("spring.flyway.password", () -> "test-migrator");
  }

  static final UUID ADMIN = new UUID(0, 1), USER = new UUID(0, 2), CHANNEL_ID = new UUID(0, 3);
  static final String CHANNEL = "UC" + "a".repeat(22), BASE = "/api/v1/admin/collection-";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate runtime;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  @Autowired CollectionOperations operations;
  @Autowired CollectionQueries queries;
  JdbcTemplate owner;
  HikariDataSource source;
  WireMockServer provider;
  Instant now;
  Browser admin, user;

  record Browser(Cookie cookie, String csrf) {}

  @BeforeEach
  void setup() throws Exception {
    now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    var ds =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    owner = new JdbcTemplate(ds);
    owner.execute(
        "TRUNCATE app.app_user,app.channel,app.song_entry,app.collection_run,app.discovery_run,app.review_item,app.collection_retry_request,session.spring_session CASCADE");
    for (String script :
        List.of(
            "collector-grants.sql",
            "statistics-collector-grants.sql",
            "discovery-grants.sql",
            "collection-operations-grants.sql"))
      new ResourceDatabasePopulator(new ClassPathResource(script)).execute(ds);
    owner.update(
        "INSERT INTO app.app_user(id,google_subject,email,role) VALUES(?,'admin','admin@example.invalid','ADMIN')",
        ADMIN);
    owner.update(
        "INSERT INTO app.app_user(id,google_subject,email) VALUES(?,'user','user@example.invalid')",
        USER);
    owner.update(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES(?,?,'test','GROUP',true)",
        CHANNEL_ID,
        CHANNEL);
    for (int n = 1; n <= 2; n++) {
      UUID song = new UUID(1, n), video = new UUID(2, n);
      owner.update(
          "INSERT INTO app.song_entry(id,title,search_title,song_type) VALUES(?,'manual','manual','COVER')",
          song);
      owner.update(
          "INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title) VALUES(?,?,?,?,'OFFICIAL_COVER','PUBLIC','old')",
          video,
          song,
          youtube(n),
          CHANNEL_ID);
    }
    var cfg = new HikariConfig();
    cfg.setJdbcUrl(postgres.getJdbcUrl());
    cfg.setUsername("stelody_collector");
    cfg.setPassword("test-collector");
    cfg.setMaximumPoolSize(2);
    cfg.setMinimumIdle(0);
    cfg.setConnectionTimeout(1000);
    source = new HikariDataSource(cfg);
    provider = new WireMockServer(0);
    provider.start();
    provider.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/videos"))
            .withQueryParam("id", equalTo(youtube(2)))
            .willReturn(okJson(videos(2))));
    provider.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/videos"))
            .withQueryParam("id", equalTo(youtube(101)))
            .willReturn(okJson(videos(101))));
    provider.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/channels"))
            .willReturn(
                okJson(
                    "{\"kind\":\"youtube#channelListResponse\",\"items\":[{\"id\":\""
                        + CHANNEL
                        + "\",\"contentDetails\":{\"relatedPlaylists\":{\"uploads\":\"UUaaaaaaaaaaaaaaaaaaaaaa\"}}}]}")));
    provider.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/playlistItems"))
            .willReturn(
                okJson(
                    "{\"kind\":\"youtube#playlistItemListResponse\",\"items\":[{\"snippet\":{\"playlistId\":\"UUaaaaaaaaaaaaaaaaaaaaaa\",\"channelId\":\""
                        + CHANNEL
                        + "\",\"resourceId\":{\"kind\":\"youtube#video\",\"videoId\":\""
                        + youtube(101)
                        + "\"}}}]}")));
    admin = browser(ADMIN);
    user = browser(USER);
  }

  @AfterEach
  void stop() {
    provider.stop();
    source.close();
  }

  String youtube(int n) {
    return "YT%09d".formatted(n);
  }

  String videos(int... ids) {
    var items = new ArrayList<Object>();
    for (int n : ids)
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
                  "2026-01-01T00:00:00Z",
                  "thumbnails",
                  Map.of()),
              "contentDetails",
              Map.of("duration", "PT2M"),
              "status",
              Map.of("privacyStatus", "public", "embeddable", true),
              "statistics",
              Map.of("viewCount", "200")));
    return mapper.writeValueAsString(Map.of("kind", "youtube#videoListResponse", "items", items));
  }

  Browser browser(UUID id) throws Exception {
    return browser(sessions, id);
  }

  <S extends Session> Browser browser(FindByIndexNameSessionRepository<S> repo, UUID id)
      throws Exception {
    var session = repo.createSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(id, Instant.now()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    repo.save(session);
    var cookie =
        new Cookie(
            "SESSION",
            Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    var result =
        mvc.perform(get("/api/v1/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();
    return new Browser(
        cookie, mapper.readTree(result.getResponse().getContentAsString()).path("token").asText());
  }

  JsonNode json(MockHttpServletRequestBuilder request, Browser browser, int status)
      throws Exception {
    if (browser != null) request.cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf());
    var response =
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse();
    return mapper.readTree(response.getContentAsString());
  }

  UUID failedVideo() {
    UUID id = UUID.randomUUID();
    var slot = now.truncatedTo(ChronoUnit.HOURS).minusSeconds(3600);
    owner.update(
        "INSERT INTO app.collection_run(id,logical_slot,status,attempt,started_at,finished_at,error_code) VALUES(?,?,'FAILED',1,?,?,'HTTP_503')",
        id,
        Timestamp.from(slot),
        Timestamp.from(now.minusSeconds(1800)),
        Timestamp.from(now.minusSeconds(1200)));
    for (int n = 1; n <= 2; n++)
      owner.update(
          "INSERT INTO app.collection_target(run_id,video_id,youtube_id,channel_youtube_id,outcome,observed_at,view_count) VALUES(?,?,?,?,?,?,?)",
          id,
          new UUID(2, n),
          youtube(n),
          CHANNEL,
          n == 1 ? "OBSERVED" : "PENDING",
          n == 1 ? Timestamp.from(now.minusSeconds(1300)) : null,
          n == 1 ? 100L : null);
    owner.update(
        "INSERT INTO app.view_snapshot(video_id,logical_slot,observed_at,view_count) VALUES(?,?,?,100)",
        new UUID(2, 1),
        Timestamp.from(slot),
        Timestamp.from(now.minusSeconds(1300)));
    return id;
  }

  UUID failedDiscovery(String mode) {
    UUID id = UUID.randomUUID();
    owner.update(
        "INSERT INTO app.discovery_run(id,mode,channel_id,started_at,finished_at,status,error_code) VALUES(?,?,?,?,?,'FAILED','HTTP_503')",
        id,
        mode,
        mode.equals("BACKFILL") ? CHANNEL_ID : null,
        Timestamp.from(now.minusSeconds(600)),
        Timestamp.from(now.minusSeconds(300)));
    return id;
  }

  JsonNode request(String kind, UUID run, UUID key, int expectedStatus) throws Exception {
    return json(
        post(BASE + "runs/" + kind + "/" + run + "/retries")
            .content(
                mapper.writeValueAsString(
                    Map.of(
                        "requestId", key, "attempt", 1, "reason", "retry after resolving outage"))),
        admin,
        expectedStatus);
  }

  CollectorSettings settings() {
    return new CollectorSettings(
        true,
        postgres.getJdbcUrl(),
        "stelody_collector",
        "test-collector",
        "test-key",
        java.net.URI.create(provider.baseUrl()),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofMinutes(1),
        1,
        Duration.ZERO);
  }

  VideoCollector.Result consume(boolean discover) {
    return consume(now, discover).orElseThrow();
  }

  Optional<VideoCollector.Result> consume(Instant at, boolean discover) {
    return new CollectionRetryWorker(source, settings(), Clock.fixed(at, ZoneOffset.UTC), d -> {})
        .collect(new CollectionBudget(Duration.ofMinutes(1)), discover, false);
  }

  JsonNode retry(UUID id) throws Exception {
    return json(get(BASE + "retries/" + id), admin, 200);
  }

  @Test
  void emptyOverviewAndRoutesRequireCurrentAdminRole() throws Exception {
    json(get(BASE + "status"), null, 401);
    json(get(BASE + "status"), user, 403);
    var response = json(get(BASE + "status"), admin, 200);
    assertThat(response.path("video").path("latest").isNull()).isTrue();
    assertThat(response.path("video").path("lastSuccessAt").isNull()).isTrue();
    assertThat(response.path("video").path("delayed").asBoolean()).isFalse();
    assertThat(response.path("activeRetry").isNull()).isTrue();
    assertThat(response.path("pendingReviews").asLong()).isZero();
    owner.update("UPDATE app.app_user SET role='USER' WHERE id=?", ADMIN);
    json(get(BASE + "status"), admin, 403);
    json(get(BASE + "runs"), admin, 403);
  }

  @Test
  void listsDetailProgressAndMissingInvalidRequestsAreExplicit() throws Exception {
    UUID run = failedVideo();
    var item = json(get(BASE + "runs/VIDEO/" + run), admin, 200);
    assertThat(item.path("totalCount").asInt()).isEqualTo(2);
    assertThat(item.path("observedCount").asInt()).isEqualTo(1);
    assertThat(item.path("pendingCount").asInt()).isEqualTo(1);
    var list = json(get(BASE + "runs").param("status", "FAILED").param("size", "1"), admin, 200);
    assertThat(list.path("items").size()).isEqualTo(1);
    assertThat(list.path("hasNext").asBoolean()).isFalse();
    failedDiscovery("NEW");
    assertThat(json(get(BASE + "runs").param("kind", "DISCOVERY"), admin, 200).path("items").size())
        .isEqualTo(1);
    json(get(BASE + "runs/VIDEO/" + UUID.randomUUID()), admin, 404);
    json(get(BASE + "retries/" + UUID.randomUUID()), admin, 404);
    json(get(BASE + "runs").param("kind", "arbitrary"), admin, 400);
    json(get(BASE + "runs").param("status", "QUEUED"), admin, 400);
    json(get(BASE + "runs").param("page", "-1"), admin, 400);
    json(get(BASE + "runs").param("size", "51"), admin, 400);
    assertThat(
            mvc.perform(get(BASE + "status").cookie(admin.cookie()))
                .andReturn()
                .getResponse()
                .getHeader("Cache-Control"))
        .contains("no-store");
  }

  @Test
  void missedSchedulesAndOverdueRunAreReportedWithoutRawProviderData() throws Exception {
    UUID run = failedVideo();
    owner.update(
        "UPDATE app.collection_run SET logical_slot=?,status='SUCCEEDED',finished_at=? WHERE id=?",
        Timestamp.from(now.truncatedTo(ChronoUnit.HOURS).minusSeconds(18000)),
        Timestamp.from(now.minusSeconds(17000)),
        run);
    var state = json(get(BASE + "status"), admin, 200);
    assertThat(state.path("video").path("delayed").asBoolean()).isTrue();
    assertThat(state.path("video").path("missedSlots").asLong()).isGreaterThanOrEqualTo(4);
    UUID discovery = failedDiscovery("BACKFILL");
    owner.update(
        "UPDATE app.discovery_run SET status='RUNNING',started_at=? WHERE id=?",
        Timestamp.from(now.minusSeconds(700)),
        discovery);
    state = json(get(BASE + "status"), admin, 200);
    assertThat(state.path("discovery").path("runningOverdue").asBoolean()).isTrue();
    assertThat(state.path("discovery").path("lastSuccessAt").isNull()).isTrue();
  }

  @Test
  void retryRequiresCsrfAndIsIdempotentWithAtomicAudit() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    String body =
        mapper.writeValueAsString(Map.of("requestId", key, "attempt", 1, "reason", "retry"));
    mvc.perform(
            post(BASE + "runs/VIDEO/" + run + "/retries")
                .cookie(admin.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    json(post(BASE + "runs/VIDEO/" + run + "/retries").content(body), user, 403);
    assertThat(request("VIDEO", run, key, 202).path("status").asText()).isEqualTo("QUEUED");
    assertThat(request("VIDEO", run, key, 202).path("id").asText()).isEqualTo(key.toString());
    assertThat(
            runtime.queryForObject("SELECT count(*) FROM app.collection_retry_request", Long.class))
        .isEqualTo(1);
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE target_type='COLLECTION_RETRY'",
                Long.class))
        .isEqualTo(1);
    request("DISCOVERY", UUID.randomUUID(), key, 409);
    request("VIDEO", run, UUID.randomUUID(), 409);
    json(
        post(BASE + "runs/VIDEO/" + run + "/retries")
            .content(
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"attempt\":1,\"reason\":\" \"}"),
        admin,
        400);
    assertThatThrownBy(
            () ->
                new CollectionOperations(queries, false, false)
                    .request(
                        "VIDEO",
                        run,
                        ADMIN,
                        new CollectionDtos.RetryInput(UUID.randomUUID(), 1, "retry")))
        .isInstanceOfSatisfying(
            CollectionException.class,
            e -> assertThat(e.code()).isEqualTo("COLLECTION_RETRY_DISABLED"));
  }

  @Test
  void expiredRunningSuccessfulAndChangedAttemptsCannotBeQueued() throws Exception {
    UUID run = failedVideo();
    owner.update("UPDATE app.collection_run SET attempt=2 WHERE id=?", run);
    assertThat(request("VIDEO", run, UUID.randomUUID(), 409).path("code").asText())
        .isEqualTo("COLLECTION_RUN_CHANGED");
    owner.update("UPDATE app.collection_run SET attempt=1,status='RUNNING' WHERE id=?", run);
    assertThat(request("VIDEO", run, UUID.randomUUID(), 409).path("code").asText())
        .isEqualTo("COLLECTION_RUN_NOT_RETRYABLE");
    owner.update("UPDATE app.collection_run SET status='SUCCEEDED' WHERE id=?", run);
    request("VIDEO", run, UUID.randomUUID(), 409);
    owner.update(
        "UPDATE app.collection_run SET status='FAILED',logical_slot=? WHERE id=?",
        Timestamp.from(now.truncatedTo(ChronoUnit.HOURS).minusSeconds(49 * 3600)),
        run);
    assertThat(request("VIDEO", run, UUID.randomUUID(), 409).path("code").asText())
        .isEqualTo("COLLECTION_RETRY_EXPIRED");
    request("VIDEO", UUID.randomUUID(), UUID.randomUUID(), 404);
  }

  @Test
  void concurrentRequestsProduceOneQueueEntryAndOneAudit() throws Exception {
    UUID run = failedVideo();
    var input = new CollectionDtos.RetryInput(UUID.randomUUID(), 1, "retry");
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs = new ArrayList<Future<UUID>>();
      for (int n = 0; n < 2; n++)
        jobs.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return operations.request("VIDEO", run, ADMIN, input).id();
                }));
      assertThat(jobs.get(0).get(10, TimeUnit.SECONDS))
          .isEqualTo(jobs.get(1).get(10, TimeUnit.SECONDS));
    }
    assertThat(
            runtime.queryForObject("SELECT count(*) FROM app.collection_retry_request", Long.class))
        .isEqualTo(1);
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE target_type='COLLECTION_RETRY'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void collectorResumesOnlyPendingVideoTargetsAndReportsResult() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    request("VIDEO", run, key, 202);
    var result = consume(true);
    assertThat(result.status()).as(result.toString()).isEqualTo("SUCCEEDED");
    assertThat(result.runId()).isEqualTo(run);
    var done = retry(key);
    assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(done.path("executionRunId").asText()).isEqualTo(run.toString());
    assertThat(
            runtime.queryForObject(
                "SELECT attempt FROM app.collection_run WHERE id=?", Integer.class, run))
        .isEqualTo(2);
    provider.verify(
        1, getRequestedFor(urlPathEqualTo("/videos")).withQueryParam("id", equalTo(youtube(2))));
    assertThat(
            runtime.queryForObject(
                "SELECT view_count FROM app.view_snapshot WHERE video_id=?",
                Long.class,
                new UUID(2, 1)))
        .isEqualTo(100L);
    assertThat(consume(now, true)).isEmpty();
    provider.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
    assertThat(request("VIDEO", run, key, 202).path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void quotaFailureIsVisibleAndNoNewRequestAutomaticallyRepeatsIt() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    request("VIDEO", run, key, 202);
    provider.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/videos"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}")));
    assertThat(consume(true).status()).isEqualTo("QUOTA_EXHAUSTED");
    var failed = retry(key);
    assertThat(failed.path("status").asText()).isEqualTo("FAILED");
    assertThat(failed.path("errorCode").asText()).isEqualTo("QUOTA_EXHAUSTED");
    assertThat(consume(now, true)).isEmpty();
    provider.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void existingCollectorLockKeepsRequestQueued() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    request("VIDEO", run, key, 202);
    try (var lock = CollectionLock.acquire(source)) {
      assertThat(lock).isNotNull();
      assertThat(consume(true).status()).isEqualTo("SKIPPED_LOCKED");
    }
    assertThat(retry(key).path("status").asText()).isEqualTo("QUEUED");
    provider.verify(0, getRequestedFor(urlPathEqualTo("/videos")));
    assertThat(consume(true).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void interruptedRequestResumesAndAlreadyCompletedRunIsNotDuplicated() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    request("VIDEO", run, key, 202);
    owner.update(
        "UPDATE app.collection_retry_request SET status='RUNNING',execution_run_id=?,lease_token=? WHERE id=?",
        run,
        UUID.randomUUID(),
        key);
    owner.update("UPDATE app.collection_run SET status='RUNNING',attempt=2 WHERE id=?", run);
    assertThat(consume(true).status()).isEqualTo("SUCCEEDED");
    assertThat(retry(key).path("status").asText()).isEqualTo("SUCCEEDED");
    provider.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void automaticCompletionBeforeConsumptionDoesNotRecollect() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    request("VIDEO", run, key, 202);
    owner.update("UPDATE app.collection_run SET status='SUCCEEDED' WHERE id=?", run);
    assertThat(consume(true).status()).isEqualTo("SKIPPED_COMPLETED");
    assertThat(retry(key).path("status").asText()).isEqualTo("SUCCEEDED");
    provider.verify(0, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void queuedRequestThatExpiresIsRejectedWithoutExternalCalls() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    request("VIDEO", run, key, 202);
    assertThat(consume(now.plusSeconds(49 * 3600), true).orElseThrow().code())
        .isEqualTo("RETRY_EXPIRED");
    assertThat(retry(key).path("status").asText()).isEqualTo("FAILED");
    provider.verify(0, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void newDiscoveryRetryUsesExistingCheckpointAndCreatesTraceableRun() throws Exception {
    UUID run = failedDiscovery("NEW"), key = UUID.randomUUID();
    request("DISCOVERY", run, key, 202);
    assertThat(consume(true).status()).isEqualTo("SUCCEEDED");
    var done = retry(key);
    assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(done.path("executionRunId").asText()).isNotEqualTo(run.toString());
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM app.review_item WHERE youtube_id=?",
                Long.class,
                youtube(101)))
        .isEqualTo(1);
    assertThat(
            runtime.queryForObject(
                "SELECT status FROM app.discovery_run WHERE id=?", String.class, run))
        .isEqualTo("FAILED");
    assertThat(consume(now, true)).isEmpty();
    var overview = json(get(BASE + "status"), admin, 200);
    assertThat(overview.path("pendingReviews").asLong()).isEqualTo(1);
    assertThat(overview.path("deferredReviews").asLong()).isZero();
    owner.update("UPDATE app.review_item SET source_observed_at=now()-interval '31 days'");
    assertThat(json(get(BASE + "status"), admin, 200).path("deferredReviews").asLong())
        .isEqualTo(1);
    owner.update("UPDATE app.review_item SET review_status='IGNORED'");
    assertThat(json(get(BASE + "status"), admin, 200).path("pendingReviews").asLong()).isZero();
  }

  @Test
  void backfillRetryKeepsChannelAndUsesSavedPageToken() throws Exception {
    UUID run = failedDiscovery("BACKFILL"), key = UUID.randomUUID();
    owner.update(
        "INSERT INTO app.discovery_channel_state(channel_id,uploads_id,head_video_id,backfill_token,backfill_complete) VALUES(?,'UUaaaaaaaaaaaaaaaaaaaaaa',?,'next-page',false)",
        CHANNEL_ID,
        youtube(999));
    request("DISCOVERY", run, key, 202);
    assertThat(consume(true).status()).isEqualTo("SUCCEEDED");
    provider.verify(
        1,
        getRequestedFor(urlPathEqualTo("/playlistItems"))
            .withQueryParam("pageToken", equalTo("next-page")));
    assertThat(
            runtime.queryForObject(
                "SELECT backfill_complete FROM app.discovery_channel_state WHERE channel_id=?",
                Boolean.class,
                CHANNEL_ID))
        .isTrue();
  }

  @Test
  void disabledDiscoveryFinishesRequestWithExplicitFailure() throws Exception {
    UUID run = failedDiscovery("NEW"), key = UUID.randomUUID();
    request("DISCOVERY", run, key, 202);
    assertThat(consume(false).code()).isEqualTo("DISCOVERY_DISABLED");
    assertThat(retry(key).path("errorCode").asText()).isEqualTo("DISCOVERY_DISABLED");
    provider.verify(0, getRequestedFor(urlPathEqualTo("/channels")));
  }

  @Test
  void scheduleWarningStartsAtThirdDueSlotAndManualChannelScanDoesNotResetIt() {
    Instant at = Instant.parse("2026-10-03T04:16:59Z");
    UUID video = failedVideo(), discovery = failedDiscovery("NEW");
    owner.update(
        "UPDATE app.collection_run SET logical_slot=?,status='SUCCEEDED' WHERE id=?",
        Timestamp.from(Instant.parse("2026-10-03T01:00:00Z")),
        video);
    owner.update(
        "UPDATE app.discovery_run SET started_at=?,status='SUCCEEDED' WHERE id=?",
        Timestamp.from(Instant.parse("2026-10-02T22:17:00Z")),
        discovery);
    UUID backfill = failedDiscovery("BACKFILL");
    owner.update("UPDATE app.discovery_run SET status='SUCCEEDED' WHERE id=?", backfill);
    for (String kind : List.of("VIDEO", "DISCOVERY")) {
      var before = queries.health(kind, at);
      assertThat(before.missedSlots()).isEqualTo(2);
      assertThat(before.delayed()).isFalse();
      var after = queries.health(kind, at.plusSeconds(1));
      assertThat(after.missedSlots()).isEqualTo(3);
      assertThat(after.delayed()).isTrue();
    }
    owner.update("UPDATE app.discovery_run SET mode='NEW' WHERE id=?", backfill);
    assertThat(queries.health("DISCOVERY", at.plusSeconds(1)).missedSlots()).isEqualTo(3);
  }

  @Test
  void springScheduleWarningsUseHourStartAndKoreanMidnight() {
    UUID video = failedVideo(), discovery = failedDiscovery("NEW");
    owner.update(
        "UPDATE app.collection_run SET logical_slot=?,status='SUCCEEDED' WHERE id=?",
        Timestamp.from(Instant.parse("2026-10-03T12:00:00Z")),
        video);
    owner.update(
        "UPDATE app.discovery_run SET started_at=?,status='SUCCEEDED' WHERE id=?",
        Timestamp.from(Instant.parse("2026-09-30T15:00:00Z")),
        discovery);
    var before = Instant.parse("2026-10-03T14:59:59Z");
    for (String kind : List.of("VIDEO", "DISCOVERY")) {
      assertThat(queries.health(kind, before, true).missedSlots()).isEqualTo(2);
      assertThat(queries.health(kind, before, true).delayed()).isFalse();
      assertThat(queries.health(kind, before.plusSeconds(1), true).missedSlots()).isEqualTo(3);
      assertThat(queries.health(kind, before.plusSeconds(1), true).delayed()).isTrue();
    }
  }

  @Test
  void auditFailureRollsBackTheAcceptedRequest() {
    UUID run = failedVideo(), key = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                operations.request(
                    "VIDEO",
                    run,
                    UUID.randomUUID(),
                    new CollectionDtos.RetryInput(key, 1, "retry")))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(
            runtime.queryForObject("SELECT count(*) FROM app.collection_retry_request", Long.class))
        .isZero();
  }

  @Test
  void queuedAttemptChangedBeforeConsumptionIsRejected() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    request("VIDEO", run, key, 202);
    owner.update("UPDATE app.collection_run SET attempt=2 WHERE id=?", run);
    assertThat(consume(true).code()).isEqualTo("RETRY_RUN_CHANGED");
    assertThat(retry(key).path("status").asText()).isEqualTo("FAILED");
    provider.verify(0, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void discoveryCompletionBeforeRequestResultIsRecoveredWithoutAnotherRun() throws Exception {
    UUID original = failedDiscovery("NEW"),
        key = UUID.randomUUID(),
        completed = failedDiscovery("NEW");
    request("DISCOVERY", original, key, 202);
    owner.update("UPDATE app.discovery_run SET status='SUCCEEDED' WHERE id=?", completed);
    owner.update(
        "UPDATE app.collection_retry_request SET status='RUNNING',execution_run_id=?,lease_token=? WHERE id=?",
        completed,
        UUID.randomUUID(),
        key);
    assertThat(consume(true).status()).isEqualTo("SKIPPED_COMPLETED");
    var result = retry(key);
    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("executionRunId").asText()).isEqualTo(completed.toString());
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.discovery_run", Long.class))
        .isEqualTo(2);
    provider.verify(0, getRequestedFor(urlPathEqualTo("/channels")));
  }

  @Test
  void concurrentCollectorsExecuteOneRequestOnce() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    request("VIDEO", run, key, 202);
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs = new ArrayList<Future<Optional<VideoCollector.Result>>>();
      for (int n = 0; n < 2; n++)
        jobs.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return consume(now, true);
                }));
      var results =
          List.of(jobs.get(0).get(10, TimeUnit.SECONDS), jobs.get(1).get(10, TimeUnit.SECONDS));
      assertThat(
              results.stream()
                  .flatMap(Optional::stream)
                  .filter(r -> r.status().equals("SUCCEEDED"))
                  .count())
          .isEqualTo(1);
    }
    assertThat(retry(key).path("status").asText()).isEqualTo("SUCCEEDED");
    provider.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void nonWebLauncherConsumesQueueUsingEnvironmentFlag() throws Exception {
    UUID run = failedVideo(), key = UUID.randomUUID();
    request("VIDEO", run, key, 202);
    owner.update(
        "UPDATE app.collection_run SET status='SUCCEEDED',logical_slot=? WHERE id=?",
        Timestamp.from(Instant.now().truncatedTo(ChronoUnit.HOURS)),
        run);
    assertThat(
            com.stelody.collector.CollectorLauncher.run(
                new String[] {
                  "--spring.profiles.active=collector",
                  "--COLLECTION_RETRY_ENABLED=true",
                  "--stelody.collector.enabled=true",
                  "--stelody.collector.db-url=" + postgres.getJdbcUrl(),
                  "--stelody.collector.db-username=stelody_collector",
                  "--stelody.collector.db-password=test-collector",
                  "--stelody.collector.api-key=test-key"
                }))
        .isZero();
    assertThat(retry(key).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.collection_run", Long.class))
        .isEqualTo(1);
  }

  @Test
  void webCannotForgeResultAndCollectorCannotReadUsersTokensOrAudit() {
    UUID run = failedVideo();
    assertThatThrownBy(
            () ->
                runtime.update("UPDATE app.collection_run SET status='SUCCEEDED' WHERE id=?", run))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> runtime.execute("UPDATE app.collection_retry_request SET status='SUCCEEDED'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                runtime.update(
                    "INSERT INTO app.collection_retry_request(id,kind,run_id,expected_attempt,status) VALUES(?,'VIDEO',?,1,'SUCCEEDED')",
                    UUID.randomUUID(),
                    run))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    var collector = new JdbcTemplate(source);
    for (String table :
        List.of(
            "app_user", "playlist", "catalog_audit", "youtube_connection", "youtube_authorization"))
      assertThatThrownBy(() -> collector.queryForList("SELECT * FROM app." + table))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                collector.execute(
                    "INSERT INTO app.collection_retry_request(id,kind,run_id,expected_attempt) VALUES(gen_random_uuid(),'VIDEO',gen_random_uuid(),1)"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
