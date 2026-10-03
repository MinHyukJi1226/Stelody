package com.stelody.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.stelody.admin.dto.CollectionRuleDtos.*;
import com.stelody.admin.dto.SpecialReviewDtos;
import com.stelody.admin.repository.SpecialReviewQueries;
import com.stelody.admin.service.CollectionRuleService;
import com.stelody.admin.service.SpecialReviewService;
import com.stelody.auth.domain.SessionUser;
import com.stelody.collector.domain.RuleConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest(properties = {"stelody.special-review.policy-allowed=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ClassificationManagementIntegrationTest {
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

  static final UUID ADMIN = new UUID(0, 1),
      USER = new UUID(0, 2),
      MEMBER = new UUID(0, 11),
      OTHER = new UUID(0, 12),
      CHANNEL = new UUID(0, 13),
      SONG = new UUID(0, 20),
      VIDEO = new UUID(0, 21);
  static final String RULES = "/api/v1/admin/collection-rules",
      SPECIAL = "/api/v1/admin/special-event-reviews";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  @Autowired JdbcTemplate runtime;
  @Autowired CollectionRuleService rules;
  @Autowired SpecialReviewService special;
  @MockitoSpyBean SpecialReviewQueries specialQueries;
  JdbcTemplate writer, collector;
  Browser admin, user;

  record Browser(Cookie cookie, String header, String token) {}

  @BeforeEach
  void setup() throws Exception {
    var owner =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    writer = new JdbcTemplate(owner);
    writer.execute(
        "TRUNCATE app.app_user,app.member,app.channel,app.song_entry,app.catalog_audit,session.spring_session CASCADE");
    writer.update(
        "UPDATE app.collection_rule SET version=0,configuration=CAST(? AS jsonb)",
        mapper.writeValueAsString(RuleConfiguration.defaults()));
    writer.execute("UPDATE app.special_event_scan_state SET last_song_id=NULL");
    writer.update(
        "INSERT INTO app.app_user(id,google_subject,email,role) VALUES(?,'admin','admin@example.invalid','ADMIN'),(?,'user','user@example.invalid','USER')",
        ADMIN,
        USER);
    Instant published = Instant.now().minusSeconds(3600);
    LocalDate date = published.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
    writer.update(
        "INSERT INTO app.member(id,name,search_name,activity_status,birthday_month,birthday_day,debut_date) VALUES(?,'member','member','ACTIVE',?,?,?)",
        MEMBER,
        date.getMonthValue(),
        date.getDayOfMonth(),
        date.minusYears(2));
    writer.update(
        "INSERT INTO app.member(id,name,search_name,activity_status,birthday_month,birthday_day) VALUES(?,'other','other','ACTIVE',?,?)",
        OTHER,
        date.getMonthValue(),
        date.getDayOfMonth());
    writer.update(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,member_id,collection_enabled) VALUES(?,'UCaaaaaaaaaaaaaaaaaaaaaa','member channel','MEMBER',?,true)",
        CHANNEL,
        OTHER);
    writer.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES(?,'A song','a song','COVER','DRAFT')",
        SONG);
    writer.update(
        "INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title,source_published_at,source_observed_at) VALUES(?,?,'abcdefghijk',?,'OFFICIAL_COVER','PUBLIC','source cover',?,now())",
        VIDEO,
        SONG,
        CHANNEL,
        java.sql.Timestamp.from(published));
    writer.update("UPDATE app.song_entry SET representative_video_id=? WHERE id=?", VIDEO, SONG);
    writer.update(
        "INSERT INTO app.song_member(song_id,member_id,position,confirmed) VALUES(?,?,0,true)",
        SONG,
        MEMBER);
    new ResourceDatabasePopulator(
            new ClassPathResource("collector-grants.sql"),
            new ClassPathResource("discovery-grants.sql"),
            new ClassPathResource("collection-rule-grants.sql"))
        .execute(owner);
    collector =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_collector", "test-collector"));
    admin = browser(ADMIN);
    user = browser(USER);
  }

  <S extends Session> Browser browser(UUID id) throws Exception {
    @SuppressWarnings("unchecked")
    var storage = (FindByIndexNameSessionRepository<S>) sessions;
    var session = storage.createSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(id, Instant.now()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    storage.save(session);
    var cookie =
        new Cookie(
            "SESSION",
            Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    var token =
        mapper.readTree(
            mvc.perform(get("/api/v1/auth/csrf").cookie(cookie))
                .andReturn()
                .getResponse()
                .getContentAsString());
    return new Browser(cookie, token.path("headerName").asText(), token.path("token").asText());
  }

  MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, Browser who) {
    return b.cookie(who.cookie()).header(who.header(), who.token());
  }

  MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, Object body) {
    return auth(b, admin).contentType("application/json").content(mapper.writeValueAsString(body));
  }

  UUID candidate() {
    return special.refresh(SONG).candidateId();
  }

  Map<String, Object> decision(UUID id, String status, String label) {
    var item = special.detail(id);
    var body = new HashMap<String, Object>();
    body.put("version", item.version());
    body.put("songVersion", item.songVersion());
    body.put("status", status);
    body.put("reason", "verified fixture");
    if (label != null) body.put("label", label);
    return body;
  }

  JsonNode update(UUID id, Map<String, Object> body, int status) throws Exception {
    return mapper.readTree(
        mvc.perform(json(patch(SPECIAL + "/" + id), body))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void routesRequireAdministratorAndCsrf() throws Exception {
    for (String path : List.of(RULES, SPECIAL)) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(auth(get(path), user)).andExpect(status().isForbidden());
      mvc.perform(auth(get(path), admin))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    mvc.perform(
            put(RULES)
                .cookie(admin.cookie())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        new Change(0L, RuleConfiguration.defaults(), "edit"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/songs/" + SONG + "/special-event-review").cookie(admin.cookie()))
        .andExpect(status().isForbidden());
    mvc.perform(patch(SPECIAL + "/" + UUID.randomUUID()).cookie(admin.cookie()))
        .andExpect(status().isForbidden());
  }

  @Test
  void previewDoesNotSaveAndPrioritizesExclusion() throws Exception {
    var preview =
        new Preview(
            0L,
            RuleConfiguration.defaults(),
            List.of(
                new Sample("Cover #Shorts", "PUBLIC"),
                new Sample("Cover", "PRIVATE"),
                new Sample("Discovery", "PUBLIC"),
                new Sample("Covered　by Singer", "PUBLIC")));
    var result =
        mapper.readTree(
            mvc.perform(json(post(RULES + "/preview"), preview))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(result.path("decisions").get(0).path("disposition").asText()).isEqualTo("EXCLUDED");
    assertThat(result.path("decisions").get(1).path("disposition").asText()).isEqualTo("DEFERRED");
    assertThat(result.path("decisions").get(2).path("suggestedType").asText()).isEqualTo("UNKNOWN");
    assertThat(result.path("decisions").get(3).path("suggestedType").asText()).isEqualTo("COVER");
    assertThat(rules.current().version()).isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class))
        .isZero();
  }

  @Test
  void ruleChangeIsVersionedAndAuditedAndStaleRequestsConflict() throws Exception {
    var body =
        new Change(0L, new RuleConfiguration(List.of(), List.of(), List.of()), "disable markers");
    mvc.perform(json(put(RULES), body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(json(put(RULES), body)).andExpect(status().isConflict());
    mvc.perform(
            json(
                post(RULES + "/preview"),
                new Preview(
                    0L, RuleConfiguration.defaults(), List.of(new Sample("Cover", "PUBLIC")))))
        .andExpect(status().isConflict());
    var audit = rules.audits(0, 20);
    assertThat(audit.items()).hasSize(1);
    assertThat(audit.items().getFirst().actorId()).isEqualTo(ADMIN);
    assertThat(collector.queryForObject("SELECT version FROM app.collection_rule", Long.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> collector.update("UPDATE app.collection_rule SET version=2"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtime.execute("DELETE FROM app.collection_rule"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> collector.queryForList("SELECT * FROM app.special_event_review"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void invalidConfigurationAndSamplesDoNotMutateDatabase() throws Exception {
    var marker = new RuleConfiguration.Marker("cover", RuleConfiguration.Match.WORD);
    for (var config :
        List.of(
            new RuleConfiguration(Collections.nCopies(31, marker), List.of(), List.of()),
            new RuleConfiguration(
                List.of(
                    marker, new RuleConfiguration.Marker("ＣＯＶＥＲ", RuleConfiguration.Match.PHRASE)),
                List.of(),
                List.of()),
            new RuleConfiguration(
                List.of(new RuleConfiguration.Marker("　", RuleConfiguration.Match.WORD)),
                List.of(),
                List.of())))
      mvc.perform(json(put(RULES), new Change(0L, config, "test")))
          .andExpect(status().isBadRequest());
    mvc.perform(
            json(
                post(RULES + "/preview"),
                new Preview(
                    0L,
                    RuleConfiguration.defaults(),
                    Collections.nCopies(21, new Sample("Cover", "PUBLIC")))))
        .andExpect(status().isBadRequest());
    assertThat(rules.current().version()).isZero();
  }

  @Test
  void failedAuditRollsBackRuleUpdate() {
    assertThatThrownBy(
            () ->
                rules.change(
                    new Change(
                        0L, new RuleConfiguration(List.of(), List.of(), List.of()), "fixture"),
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
    assertThat(rules.current().version()).isZero();
  }

  @Test
  void simultaneousRuleEditsCannotBothCommit() throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var task =
          (Callable<Boolean>)
              () -> {
                start.await();
                try {
                  rules.change(
                      new Change(
                          0L, new RuleConfiguration(List.of(), List.of(), List.of()), "race"),
                      ADMIN);
                  return true;
                } catch (com.stelody.admin.web.AdminCatalogException e) {
                  assertThat(e.status()).isEqualTo(409);
                  return false;
                }
              };
      var first = executor.submit(task);
      var second = executor.submit(task);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(rules.current().version()).isEqualTo(1);
    assertThat(rules.audits(0, 20).items()).hasSize(1);
  }

  @Test
  void dateMatchesCreateSeparatePendingCandidateWithoutPublishingOrFlagging() throws Exception {
    UUID id = candidate();
    assertThat(id).isNotNull();
    var item = special.detail(id);
    assertThat(item.status()).isEqualTo(SpecialReviewDtos.Status.PENDING);
    assertThat(item.isSpecialEvent()).isFalse();
    assertThat(item.evidence())
        .hasSize(2)
        .allSatisfy(e -> assertThat(e.memberId()).isEqualTo(MEMBER));
    assertThat(item.basisCurrent()).isTrue();
    assertThat(item.songVersion()).isZero();
    assertThat(
            writer.queryForObject(
                "SELECT visibility FROM app.song_entry WHERE id=?", String.class, SONG))
        .isEqualTo("DRAFT");
    assertThat(candidate()).isEqualTo(id);
    assertThat(special.detail(id).version()).isZero();
    mvc.perform(auth(get(SPECIAL + "/" + id), admin))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void unconfirmedParticipantsAndChannelOwnerNeverInferDates() {
    writer.update("UPDATE app.song_member SET confirmed=false WHERE song_id=?", SONG);
    assertThat(candidate()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"representative", "source-expired", "private", "future", "date-missing"})
  void incompleteOrUnavailableBasisCreatesNoCandidate(String condition) {
    switch (condition) {
      case "representative" ->
          writer.update("UPDATE app.song_entry SET representative_video_id=NULL WHERE id=?", SONG);
      case "source-expired" ->
          writer.update(
              "UPDATE app.video SET source_observed_at=now()-interval '31 days' WHERE id=?", VIDEO);
      case "private" ->
          writer.update("UPDATE app.video SET availability='PRIVATE' WHERE id=?", VIDEO);
      case "future" ->
          writer.update(
              "UPDATE app.video SET source_published_at=now()+interval '1 day' WHERE id=?", VIDEO);
      case "date-missing" ->
          writer.update(
              "UPDATE app.member SET birthday_month=NULL,birthday_day=NULL,debut_date=NULL WHERE id=?",
              MEMBER);
    }
    assertThat(candidate()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "PENDING,representative",
    "PENDING,participants",
    "PENDING,dates",
    "CONFIRMED,representative",
    "CONFIRMED,participants",
    "CONFIRMED,dates",
    "DISMISSED,representative",
    "DISMISSED,participants",
    "DISMISSED,dates"
  })
  void refreshWithoutCurrentMatchReturnsNullAndKeepsManualDecision(
      String reviewStatus, String condition) throws Exception {
    UUID id = candidate();
    if (!reviewStatus.equals("PENDING"))
      update(id, decision(id, reviewStatus, reviewStatus.equals("CONFIRMED") ? "기념곡" : null), 200);
    var before = specialQueries.get(id);
    var songBefore = special.detail(id);
    switch (condition) {
      case "representative" ->
          writer.update("UPDATE app.song_entry SET representative_video_id=NULL WHERE id=?", SONG);
      case "participants" ->
          writer.update("UPDATE app.song_member SET confirmed=false WHERE song_id=?", SONG);
      case "dates" ->
          writer.update(
              "UPDATE app.member SET birthday_month=NULL,birthday_day=NULL,debut_date=NULL WHERE id=?",
              MEMBER);
    }
    var response =
        mapper.readTree(
            mvc.perform(auth(post("/api/v1/admin/songs/" + SONG + "/special-event-review"), admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(response.get("candidateId").isNull()).isTrue();
    var after = specialQueries.get(id);
    assertThat(after.id()).isEqualTo(id);
    assertThat(after.status()).isEqualTo(before.status());
    if (reviewStatus.equals("PENDING")) {
      assertThat(after.active()).isFalse();
      assertThat(after.evidence()).isEmpty();
      assertThat(after.version()).isEqualTo(before.version() + 1);
    } else {
      assertThat(after).isEqualTo(before);
    }
    var songAfter = special.detail(id);
    assertThat(songAfter.isSpecialEvent()).isEqualTo(songBefore.isSpecialEvent());
    assertThat(songAfter.specialEventLabel()).isEqualTo(songBefore.specialEventLabel());
    assertThat(songAfter.songVersion()).isEqualTo(songBefore.songVersion());
    assertThat(special.audits(id, 0, 20).items()).hasSize(reviewStatus.equals("PENDING") ? 0 : 1);
  }

  void expireAfterCandidateRead(UUID id) {
    writer.update(
        "UPDATE app.special_event_review SET source_expires_at=now()-interval '1 second' WHERE id=?",
        id);
    var once = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              var previous = invocation.callRealMethod();
              if (once.compareAndSet(true, false))
                CompletableFuture.runAsync(special::expire).get(5, TimeUnit.SECONDS);
              return previous;
            })
        .when(specialQueries)
        .forSong(SONG);
  }

  void assertExpiredThenRefreshable(UUID id) {
    var expired = specialQueries.get(id);
    assertThat(expired.version()).isEqualTo(1);
    assertThat(expired.active()).isFalse();
    assertThat(expired.evidence()).isEmpty();
    assertThat(candidate()).isEqualTo(id);
    var restored = special.detail(id);
    assertThat(restored.version()).isEqualTo(2);
    assertThat(restored.basisCurrent()).isTrue();
    assertThat(restored.evidence()).hasSize(2);
  }

  @Test
  void refreshConflictingWithExpirationReturnsConflictAndCanBeRetried() throws Exception {
    UUID id = candidate();
    expireAfterCandidateRead(id);
    mvc.perform(auth(post("/api/v1/admin/songs/" + SONG + "/special-event-review"), admin))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CATALOG_VERSION_CONFLICT"));
    assertExpiredThenRefreshable(id);
  }

  @Test
  void scanConflictingWithExpirationRollsBackCursorAndCanBeRetried() {
    UUID id = candidate();
    UUID cursor = new UUID(0, 19);
    writer.update("UPDATE app.special_event_scan_state SET last_song_id=?", cursor);
    expireAfterCandidateRead(id);
    assertThatThrownBy(special::scan)
        .isInstanceOf(com.stelody.admin.web.AdminCatalogException.class)
        .satisfies(
            e ->
                assertThat(((com.stelody.admin.web.AdminCatalogException) e).status())
                    .isEqualTo(409));
    assertThat(
            writer.queryForObject(
                "SELECT last_song_id FROM app.special_event_scan_state", UUID.class))
        .isEqualTo(cursor);
    var expired = specialQueries.get(id);
    assertThat(expired.version()).isEqualTo(1);
    assertThat(expired.active()).isFalse();
    assertThat(expired.evidence()).isEmpty();
    special.scan();
    assertThat(
            writer.queryForObject(
                "SELECT last_song_id FROM app.special_event_scan_state", UUID.class))
        .isNull();
    assertThat(special.detail(id).version()).isEqualTo(2);
    assertThat(special.detail(id).basisCurrent()).isTrue();
    assertThat(special.detail(id).evidence()).hasSize(2);
  }

  @Test
  void confirmationSetsOneFreeTextLabelAndAuditsTheSongAndReview() throws Exception {
    UUID id = candidate();
    var result = update(id, decision(id, "CONFIRMED", "생일과 데뷔 기념"), 200);
    assertThat(result.path("isSpecialEvent").asBoolean()).isTrue();
    assertThat(result.path("specialEventLabel").asText()).isEqualTo("생일과 데뷔 기념");
    assertThat(result.path("songVersion").asLong()).isEqualTo(1);
    assertThat(result.path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(special.audits(id, 0, 20).items()).hasSize(1);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class))
        .isEqualTo(2);
    var audit = mapper.writeValueAsString(special.audits(id, 0, 20));
    assertThat(audit).doesNotContain("publishedDate", "videoId", "sourceTitle");
  }

  @Test
  void dismissalAndManualUnsetSurviveRecollection() throws Exception {
    UUID id = candidate();
    update(id, decision(id, "DISMISSED", null), 200);
    writer.update("UPDATE app.video SET source_observed_at=now() WHERE id=?", VIDEO);
    special.scan();
    assertThat(special.detail(id).status()).isEqualTo(SpecialReviewDtos.Status.DISMISSED);
    assertThat(special.detail(id).isSpecialEvent()).isFalse();
    update(id, decision(id, "PENDING", null), 200);
    update(id, decision(id, "CONFIRMED", "기념곡"), 200);
    writer.update(
        "UPDATE app.song_entry SET is_special_event=false,special_event_label=NULL,version=version+1 WHERE id=?",
        SONG);
    special.scan();
    assertThat(special.detail(id).status()).isEqualTo(SpecialReviewDtos.Status.CONFIRMED);
    assertThat(special.detail(id).isSpecialEvent()).isFalse();
  }

  @Test
  void oldEvidenceCannotBeConfirmedAndPendingRefreshAdvancesVersion() throws Exception {
    UUID id = candidate();
    var body = decision(id, "CONFIRMED", "기념곡");
    writer.update(
        "UPDATE app.member SET debut_date=debut_date-interval '1 day' WHERE id=?", MEMBER);
    update(id, body, 409);
    assertThat(special.detail(id).basisCurrent()).isFalse();
    candidate();
    assertThat(special.detail(id).version()).isEqualTo(1);
    update(id, body, 409);
    update(id, decision(id, "CONFIRMED", "생일곡"), 200);
  }

  @Test
  void staleSongVersionAndBlankLabelAreRejected() throws Exception {
    UUID id = candidate();
    update(id, decision(id, "CONFIRMED", "　"), 400);
    var body = decision(id, "CONFIRMED", "기념곡");
    writer.update("UPDATE app.song_entry SET version=version+1 WHERE id=?", SONG);
    update(id, body, 409);
    assertThat(special.detail(id).isSpecialEvent()).isFalse();
  }

  @Test
  void expirationHidesAndRemovesSourceEvidenceButKeepsManualDecision() throws Exception {
    UUID id = candidate();
    update(id, decision(id, "DISMISSED", null), 200);
    writer.update(
        "UPDATE app.special_event_review SET source_expires_at=now()-interval '1 second' WHERE id=?",
        id);
    assertThat(special.detail(id).evidence()).isEmpty();
    special.expire();
    assertThat(
            writer.queryForObject(
                "SELECT evidence::text FROM app.special_event_review WHERE id=?", String.class, id))
        .isEqualTo("[]");
    assertThat(special.detail(id).status()).isEqualTo(SpecialReviewDtos.Status.DISMISSED);
    update(id, decision(id, "PENDING", null), 200);
    assertThat(special.detail(id).evidence()).hasSize(2);
    assertThat(special.detail(id).basisCurrent()).isTrue();
  }

  @Test
  void manualPublishedDateOverridesSourceDate() {
    writer.update(
        "UPDATE app.video SET published_at=source_published_at-interval '1 day' WHERE id=?", VIDEO);
    assertThat(candidate()).isNull();
  }

  @Test
  void failedAuditRollsBackBadgeAndCandidateDecision() {
    UUID id = candidate();
    var item = special.detail(id);
    assertThatThrownBy(
            () ->
                special.change(
                    id,
                    new SpecialReviewDtos.Change(
                        item.version(),
                        item.songVersion(),
                        SpecialReviewDtos.Status.CONFIRMED,
                        "기념곡",
                        "test"),
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
    assertThat(special.detail(id).isSpecialEvent()).isFalse();
    assertThat(special.detail(id).version()).isZero();
  }

  @Test
  void boundedScanResumesUntilAllSongsAreChecked() {
    for (int i = 1; i <= 55; i++)
      writer.update(
          "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES(?,'draft','draft','COVER','DRAFT')",
          new UUID(0, 100 + i));
    special.scan();
    assertThat(
            writer.queryForObject(
                "SELECT last_song_id FROM app.special_event_scan_state", UUID.class))
        .isNotNull();
    assertThat(special.detail(candidate()).basisCurrent()).isTrue();
    special.scan();
    assertThat(
            writer.queryForObject(
                "SELECT last_song_id FROM app.special_event_scan_state", UUID.class))
        .isNull();
  }

  @Test
  void collaborationKeepsAllMatchingParticipantsUnderOneCandidate() throws Exception {
    writer.update(
        "INSERT INTO app.song_member(song_id,member_id,position,confirmed) VALUES(?,?,1,true)",
        SONG,
        OTHER);
    UUID id = candidate();
    assertThat(special.detail(id).evidence())
        .hasSize(3)
        .extracting(e -> e.memberId())
        .contains(MEMBER, OTHER);
    update(id, decision(id, "CONFIRMED", "합동 기념곡"), 200);
    assertThat(
            writer.queryForObject("SELECT count(*) FROM app.special_event_review", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void simultaneousConfirmationsCannotBothCommit() throws Exception {
    UUID id = candidate();
    var item = special.detail(id);
    var input =
        new SpecialReviewDtos.Change(
            item.version(), item.songVersion(), SpecialReviewDtos.Status.CONFIRMED, "기념곡", "race");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var task =
          (Callable<Boolean>)
              () -> {
                start.await();
                try {
                  special.change(id, input, ADMIN);
                  return true;
                } catch (com.stelody.admin.web.AdminCatalogException e) {
                  assertThat(e.status()).isEqualTo(409);
                  return false;
                }
              };
      var first = executor.submit(task);
      var second = executor.submit(task);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(special.detail(id).songVersion()).isEqualTo(1);
    assertThat(special.audits(id, 0, 20).items()).hasSize(1);
  }
}
