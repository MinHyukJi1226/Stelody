package com.stelody;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.stelody.auth.domain.SessionUser;
import com.stelody.review.dto.ReviewDtos;
import com.stelody.review.repository.ReviewRepository;
import com.stelody.review.service.ReviewService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ReviewIntegrationTest {
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
      CHANNEL = new UUID(0, 3),
      REVIEW = new UUID(0, 4);
  static final String ROOT = "/api/v1/admin/reviews";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  @Autowired ReviewService service;
  @Autowired com.stelody.review.service.ReviewBatchService batches;
  @Autowired ReviewRepository repository;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate runtime;
  JdbcTemplate writer;
  Browser admin, user;

  record Browser(Cookie cookie, String csrfHeader, String csrf) {}

  @BeforeEach
  void setup() throws Exception {
    writer =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    writer.execute(
        "TRUNCATE app.app_user,app.channel,app.song_entry,session.spring_session CASCADE");
    writer.update(
        "INSERT INTO app.app_user(id,google_subject,email,role) VALUES(?,'admin','admin@example.invalid','ADMIN'),(?,'user','user@example.invalid','USER')",
        ADMIN,
        USER);
    writer.update(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES(?,'UCaaaaaaaaaaaaaaaaaaaaaa','Test channel','GROUP',true)",
        CHANNEL);
    seed(REVIEW, 1);
    admin = browser(sessions, ADMIN);
    user = browser(sessions, USER);
  }

  void seed(UUID id, int n) {
    writer.update(
        """
        INSERT INTO app.review_item(id,youtube_id,channel_id,source_title,source_published_at,source_observed_at,
          availability,disposition,suggested_type,rule_version,decision_reason,first_seen_at)
        VALUES(?,?,?,'Test cover',now(),now(),'PUBLIC','REVIEW','COVER','title-v1','COVER_REQUIRES_PARTICIPANT_REVIEW',now())
        """,
        id,
        "YT%09d".formatted(n),
        CHANNEL);
  }

  <S extends Session> Browser browser(FindByIndexNameSessionRepository<S> storage, UUID account)
      throws Exception {
    var session = storage.createSession();
    var context = SecurityContextHolder.createEmptyContext();
    // Deliberately claim ADMIN for a USER account too; the filter must use current DB roles.
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(account, Instant.now()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    storage.save(session);
    Cookie cookie =
        new Cookie(
            "SESSION",
            Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    var response =
        mapper.readTree(
            mvc.perform(get("/api/v1/auth/csrf").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return new Browser(
        cookie, response.path("headerName").asText(), response.path("token").asText());
  }

  MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Browser who) {
    return request.cookie(who.cookie()).header(who.csrfHeader(), who.csrf());
  }

  MockHttpServletRequestBuilder change(long version, String state, String note) {
    return auth(patch(ROOT + "/" + REVIEW), admin)
        .contentType("application/json")
        .content(
            mapper.writeValueAsString(Map.of("version", version, "status", state, "reason", note)));
  }

  UUID singer() {
    UUID member = new UUID(0, 77);
    writer.update(
        "INSERT INTO app.member(id,name,search_name,activity_status) VALUES(?,'검토 가수','검토가수','ACTIVE') ON CONFLICT(id) DO NOTHING",
        member);
    return member;
  }

  ReviewDtos.Complete batch(List<UUID> ids) {
    return new ReviewDtos.Complete(
        UUID.randomUUID(),
        service.completionPreview().items().stream()
            .map(i -> new ReviewDtos.Candidate(i.id(), i.version()))
            .toList(),
        ids.stream().map(ReviewDtos.Selection::new).toList());
  }

  @Test
  void selectedSongsRegisterAndOnlyUnselectedSnapshotIsDeletedWithReplayProtection()
      throws Exception {
    UUID second = new UUID(0, 5), discard = new UUID(0, 6), fresh = new UUID(0, 7);
    seed(second, 5);
    seed(discard, 6);
    var body = batch(List.of(REVIEW, second));
    seed(fresh, 7);
    writer.update(
        "INSERT INTO app.admin_audit(id,review_id,before_status,after_status,after_note,changed_at) VALUES(?,?,'PENDING','PENDING','reviewed',now())",
        UUID.randomUUID(),
        discard);
    String json = mapper.writeValueAsString(body);
    mvc.perform(
            auth(post(ROOT + "/completion"), admin).contentType("application/json").content(json))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.registeredSongs.length()").value(2))
        .andExpect(jsonPath("$.deletedCount").value(1))
        .andExpect(jsonPath("$.replayed").value(false));
    mvc.perform(
            auth(post(ROOT + "/completion"), admin).contentType("application/json").content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registeredSongs.length()").value(2))
        .andExpect(jsonPath("$.deletedCount").value(0))
        .andExpect(jsonPath("$.replayed").value(true));
    assertThat(writer.queryForList("SELECT id FROM app.review_item", UUID.class))
        .containsExactlyInAnyOrder(REVIEW, second, fresh);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Integer.class))
        .isEqualTo(2);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.video", Integer.class)).isEqualTo(2);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.admin_audit", Integer.class))
        .isZero();
    mvc.perform(get("/api/v1/songs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    assertThat(writer.queryForList("SELECT visibility FROM app.song_entry", String.class))
        .containsOnly("DRAFT");
    var changed =
        new ReviewDtos.Complete(
            body.requestId(), body.items(), List.of(new ReviewDtos.Selection(REVIEW)));
    mvc.perform(
            auth(post(ROOT + "/completion"), admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(changed)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_VERSION_CONFLICT"));
    var reused =
        new ReviewDtos.Complete(
            body.requestId(),
            List.of(new ReviewDtos.Candidate(fresh, 0L)),
            List.of(new ReviewDtos.Selection(fresh)));
    mvc.perform(
            auth(post(ROOT + "/completion"), admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(reused)))
        .andExpect(status().isConflict());
  }

  @Test
  void unselectedCandidateRegisteredByAnotherOperationIsPreserved() throws Exception {
    UUID other = new UUID(0, 5);
    seed(other, 5);
    var body = batch(List.of(REVIEW));
    writer.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES(?,'기존 곡','기존곡','COVER','DRAFT')",
        other);
    writer.update(
        "INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title,source_observed_at,status_observed_at) VALUES(?,?,?,?,'OFFICIAL_COVER','PUBLIC','기존 영상',now(),now())",
        other,
        other,
        "YT000000005",
        CHANNEL);
    writer.update(
        "UPDATE app.review_item SET review_status='REGISTERED',registered_video_id=?,version=version+1 WHERE id=?",
        other,
        other);
    mvc.perform(
            auth(post(ROOT + "/completion"), admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registeredSongs.length()").value(1))
        .andExpect(jsonPath("$.deletedCount").value(0));
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.review_item WHERE review_status='REGISTERED'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            writer.queryForObject(
                "SELECT title FROM app.song_entry WHERE id=?", String.class, other))
        .isEqualTo("기존 곡");
  }

  @Test
  void nestedInputErrorsIdentifySelectedCandidateWithoutChangingAnything() throws Exception {
    var valid = batch(List.of(REVIEW));
    var invalid =
        new ReviewDtos.Complete(
            valid.requestId(), valid.items(), List.of(new ReviewDtos.Selection(null)));
    mvc.perform(
            auth(post(ROOT + "/completion"), admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("selected[0].id"));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Integer.class))
        .isZero();
    assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
        .isEqualTo("PENDING");
  }

  @Test
  void failureInSecondRegistrationRollsBackFirstRegistrationAndAllDeletion() throws Exception {
    UUID second = new UUID(0, 5);
    seed(second, 5);
    seed(new UUID(0, 6), 6);
    writer.update("UPDATE app.review_item SET source_published_at=NULL WHERE id=?", second);
    var body = batch(List.of(REVIEW, second));
    mvc.perform(
            auth(post(ROOT + "/completion"), admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CANDIDATE_REQUIRES_REFRESH"));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Integer.class))
        .isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.video", Integer.class)).isZero();
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.review_item WHERE review_status='PENDING'",
                Integer.class))
        .isEqualTo(3);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class))
        .isZero();
  }

  @Test
  void selectedIdAloneBuildsConfirmedSoloDraftAndServerAuditWithoutChangingPublicationControl()
      throws Exception {
    UUID member = singer();
    writer.update(
        "UPDATE app.channel SET channel_type='MEMBER',member_id=? WHERE id=?", member, CHANNEL);
    writer.update(
        "UPDATE app.review_item SET source_title='Milky Way / 검토 가수 Cover' WHERE id=?", REVIEW);
    writer.execute("UPDATE app.cover_publication_control SET enabled=true");
    long controlVersion =
        writer.queryForObject("SELECT version FROM app.cover_publication_control", Long.class);
    var body = batch(List.of(REVIEW));
    var response =
        mapper.readTree(
            mvc.perform(
                    auth(post(ROOT + "/completion"), admin)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    UUID song = UUID.fromString(response.at("/registeredSongs/0/songId").asText());
    mvc.perform(auth(get("/api/v1/admin/songs/" + song), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Milky Way"))
        .andExpect(jsonPath("$.type").value("COVER"))
        .andExpect(jsonPath("$.visibility").value("DRAFT"))
        .andExpect(jsonPath("$.memberIds[0]").value(member.toString()))
        .andExpect(jsonPath("$.representativeVideoId").isNotEmpty());
    assertThat(writer.queryForObject("SELECT source_title FROM app.video", String.class))
        .isEqualTo("Milky Way / 검토 가수 Cover");
    assertThat(writer.queryForObject("SELECT confirmed FROM app.song_member", Boolean.class))
        .isTrue();
    assertThat(
            writer.queryForObject(
                "SELECT enabled FROM app.cover_publication_control", Boolean.class))
        .isTrue();
    assertThat(
            writer.queryForObject("SELECT version FROM app.cover_publication_control", Long.class))
        .isEqualTo(controlVersion);
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.cover_auto_registration", Integer.class))
        .isZero();
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE action='BATCH_REGISTER' AND target_id=? AND actor_id=? AND reason=?",
                Integer.class,
                body.requestId(),
                ADMIN,
                com.stelody.review.domain.ReviewRegistrationRules.REASON))
        .isEqualTo(1);
    int auditCount = writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class);
    writer.update("UPDATE app.song_entry SET title='관리자 수정 곡명' WHERE id=?", song);
    writer.update(
        "UPDATE app.review_item SET suggested_type='UNKNOWN',source_title='변경된 영상' WHERE id=?",
        REVIEW);
    mvc.perform(
            auth(post(ROOT + "/completion"), admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(true))
        .andExpect(jsonPath("$.deletedCount").value(0));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class))
        .isEqualTo(auditCount);
    assertThat(writer.queryForObject("SELECT title FROM app.song_entry", String.class))
        .isEqualTo("관리자 수정 곡명");
    writer.execute("UPDATE app.cover_publication_control SET enabled=false");
  }

  @Test
  void groupDuetAndExternalCreditsStayUnresolvedAndVisibleToAdminAsMissingMembers()
      throws Exception {
    UUID member = singer();
    writer.update(
        "UPDATE app.channel SET channel_type='MEMBER',member_id=? WHERE id=?", member, CHANNEL);
    writer.update(
        "UPDATE app.review_item SET source_title='곡 / 검토 가수 & Guest Cover' WHERE id=?", REVIEW);
    var result = batches.complete(ADMIN, batch(List.of(REVIEW)));
    UUID song = result.registeredSongs().getFirst().songId();
    mvc.perform(auth(get("/api/v1/admin/songs/" + song), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memberIds").isEmpty())
        .andExpect(jsonPath("$.externalArtistIds").isEmpty())
        .andExpect(jsonPath("$.visibility").value("DRAFT"))
        .andExpect(jsonPath("$.missingFields").value(org.hamcrest.Matchers.hasItem("members")));
    mvc.perform(auth(get("/api/v1/admin/songs"), admin))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[0].missingFields").value(org.hamcrest.Matchers.hasItem("members")));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_member", Integer.class))
        .isZero();
  }

  @Test
  void unclassifiableSecondCandidateRollsBackFirstRegistrationAndAllDeletion() throws Exception {
    UUID second = new UUID(0, 5);
    seed(second, 5);
    seed(new UUID(0, 6), 6);
    writer.update(
        "UPDATE app.review_item SET suggested_type='UNKNOWN',source_title='분류할 수 없는 영상' WHERE id=?",
        second);
    var body = batch(List.of(REVIEW, second));
    mvc.perform(
            auth(post(ROOT + "/completion"), admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CANDIDATE_CLASSIFICATION_REQUIRED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("selected[1].id"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("TYPE_UNCONFIRMED"));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Integer.class))
        .isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.video", Integer.class)).isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class))
        .isZero();
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.review_item WHERE review_status='PENDING'",
                Integer.class))
        .isEqualTo(3);
  }

  @Test
  void originalWithUnconfirmedVideoKindRegistersOtherDraftWithoutInventingParticipants()
      throws Exception {
    writer.update(
        "UPDATE app.review_item SET suggested_type='ORIGINAL',source_title='새 노래 오리지널' WHERE id=?",
        REVIEW);
    var result = batches.complete(ADMIN, batch(List.of(REVIEW)));
    assertThat(writer.queryForObject("SELECT song_type FROM app.song_entry", String.class))
        .isEqualTo("ORIGINAL");
    assertThat(writer.queryForObject("SELECT visibility FROM app.song_entry", String.class))
        .isEqualTo("DRAFT");
    assertThat(writer.queryForObject("SELECT video_kind FROM app.video", String.class))
        .isEqualTo("OTHER");
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_member", Integer.class))
        .isZero();
  }

  @Test
  void deletionFailureAlsoRollsBackRegisteredSongs() throws Exception {
    seed(new UUID(0, 5), 5);
    var body = batch(List.of(REVIEW));
    writer.execute(
        "CREATE FUNCTION app.reject_candidate_deletion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture failure' USING ERRCODE='23503'; END $$");
    writer.execute(
        "CREATE TRIGGER reject_candidate_deletion BEFORE DELETE ON app.review_item FOR EACH ROW EXECUTE FUNCTION app.reject_candidate_deletion()");
    try {
      mvc.perform(
              auth(post(ROOT + "/completion"), admin)
                  .contentType("application/json")
                  .content(mapper.writeValueAsString(body)))
          .andExpect(status().isConflict());
      assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Integer.class))
          .isZero();
      assertThat(
              writer.queryForObject(
                  "SELECT count(*) FROM app.review_item WHERE review_status='PENDING'",
                  Integer.class))
          .isEqualTo(2);
    } finally {
      writer.execute("DROP TRIGGER reject_candidate_deletion ON app.review_item");
      writer.execute("DROP FUNCTION app.reject_candidate_deletion()");
    }
  }

  @Test
  void concurrentSameRequestCreatesOneSetOfSongs() throws Exception {
    seed(new UUID(0, 5), 5);
    var body = batch(List.of(REVIEW));
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var first =
          pool.submit(
              () -> {
                start.await();
                return batches.complete(ADMIN, body);
              });
      var second =
          pool.submit(
              () -> {
                start.await();
                return batches.complete(ADMIN, body);
              });
      start.countDown();
      var results =
          List.of(
              first.get(30, java.util.concurrent.TimeUnit.SECONDS),
              second.get(30, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(results)
          .extracting(ReviewDtos.CompletionResult::replayed)
          .containsExactlyInAnyOrder(true, false);
      assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Integer.class))
          .isEqualTo(1);
      assertThat(writer.queryForObject("SELECT count(*) FROM app.video", Integer.class))
          .isEqualTo(1);
    }
  }

  @Test
  void previewExcludesDeferredIgnoredExcludedAndExpiredCandidates() throws Exception {
    for (int n = 10; n < 14; n++) seed(new UUID(0, n), n);
    writer.execute(
        "UPDATE app.review_item SET disposition='DEFERRED' WHERE id='00000000-0000-0000-0000-00000000000a'");
    writer.execute(
        "UPDATE app.review_item SET disposition='EXCLUDED' WHERE id='00000000-0000-0000-0000-00000000000b'");
    writer.execute(
        "UPDATE app.review_item SET review_status='IGNORED' WHERE id='00000000-0000-0000-0000-00000000000c'");
    writer.execute(
        "UPDATE app.review_item SET source_observed_at=now()-interval '31 days' WHERE id='00000000-0000-0000-0000-00000000000d'");
    mvc.perform(auth(get(ROOT + "/completion"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.hasMore").value(false));
    assertThat(batches.complete(ADMIN, batch(List.of(REVIEW))).registeredSongs()).hasSize(1);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.review_item", Integer.class))
        .isEqualTo(5);
  }

  @Test
  void changedSnapshotRejectsAllRegistrationAndDeletion() throws Exception {
    seed(new UUID(0, 5), 5);
    var body = batch(List.of(REVIEW));
    writer.update("UPDATE app.review_item SET version=version+1 WHERE id=?", new UUID(0, 5));
    mvc.perform(
            auth(post(ROOT + "/completion"), admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_VERSION_CONFLICT"));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Integer.class))
        .isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.review_item", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void emptySelectionCannotDeleteAndAdminCsrfAndSnapshotRemainRequired() throws Exception {
    var valid = batch(List.of(REVIEW));
    String body = mapper.writeValueAsString(valid);
    mvc.perform(get(ROOT + "/completion")).andExpect(status().isUnauthorized());
    mvc.perform(auth(get(ROOT + "/completion"), user)).andExpect(status().isForbidden());
    mvc.perform(
            post(ROOT + "/completion")
                .cookie(admin.cookie())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            auth(post(ROOT + "/completion"), user).contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    for (String invalid :
        List.of(
            "{}",
            mapper.writeValueAsString(
                new ReviewDtos.Complete(valid.requestId(), valid.items(), List.of())),
            mapper.writeValueAsString(
                new ReviewDtos.Complete(
                    valid.requestId(),
                    valid.items(),
                    List.of(valid.selected().getFirst(), valid.selected().getFirst()))),
            mapper.writeValueAsString(
                new ReviewDtos.Complete(valid.requestId(), List.of(), valid.selected()))))
      mvc.perform(
              auth(post(ROOT + "/completion"), admin)
                  .contentType("application/json")
                  .content(invalid))
          .andExpect(status().isBadRequest());
    assertThat(writer.queryForObject("SELECT count(*) FROM app.review_item", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> runtime.execute("DELETE FROM app.review_item"))
        .hasRootCauseMessage("ERROR: permission denied for table review_item");
    writer.update("UPDATE app.app_user SET role='USER' WHERE id=?", ADMIN);
    assertThat(
            runtime.queryForObject(
                "SELECT app.complete_review_candidates('[]','[]',NULL,NULL,?::uuid)",
                Integer.class,
                ADMIN))
        .isEqualTo(-2);
  }

  @Test
  void adminCanPageAndReadDetailsWithoutCaching() throws Exception {
    seed(new UUID(0, 5), 2);
    mvc.perform(auth(get(ROOT).param("size", "1"), admin))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.items.length()").value(1));
    mvc.perform(auth(get(ROOT + "/" + REVIEW), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channelName").value("Test channel"))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.version").value(0));
  }

  @Test
  void anonymousAndDatabaseUserCannotReadOrModifyCandidates() throws Exception {
    mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
    mvc.perform(auth(get(ROOT), user)).andExpect(status().isForbidden());
    mvc.perform(
            auth(patch(ROOT + "/" + REVIEW), user)
                .contentType("application/json")
                .content("{\"version\":0,\"status\":\"IGNORED\",\"reason\":\"x\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(auth(post(ROOT), admin)).andExpect(status().isForbidden());
  }

  @Test
  void revokedAdminRoleAndMissingCsrfAreRejected() throws Exception {
    mvc.perform(
            patch(ROOT + "/" + REVIEW)
                .cookie(admin.cookie())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    writer.update("UPDATE app.app_user SET role='USER' WHERE id=?", ADMIN);
    mvc.perform(auth(get(ROOT), admin)).andExpect(status().isForbidden());
  }

  @Test
  void ignoreAndRestoreAreAuditedWithOptimisticVersionAndPreserveSource() throws Exception {
    mvc.perform(change(0, "IGNORED", "not a song"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IGNORED"))
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(change(0, "PENDING", "restore"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_VERSION_CONFLICT"));
    mvc.perform(change(1, "PENDING", "restore"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.admin_audit", Long.class))
        .isEqualTo(2);
    assertThat(writer.queryForList("SELECT actor_id FROM app.admin_audit", UUID.class))
        .containsOnly(ADMIN);
    assertThat(writer.queryForObject("SELECT source_title FROM app.review_item", String.class))
        .isEqualTo("Test cover");
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Long.class)).isZero();
  }

  @Test
  void repeatedIdenticalDecisionDoesNotAddAuditOrVersion() throws Exception {
    mvc.perform(change(0, "IGNORED", "not a song")).andExpect(status().isOk());
    mvc.perform(change(1, "IGNORED", "not a song"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.admin_audit", Long.class))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"status\":\"IGNORED\",\"reason\":\"not a song\"}",
        "{\"version\":null,\"status\":\"IGNORED\",\"reason\":\"not a song\"}",
        "{\"version\":-1,\"status\":\"IGNORED\",\"reason\":\"not a song\"}"
      })
  void missingNullAndNegativeVersionsCannotChangeAnInitialCandidate(String body) throws Exception {
    mvc.perform(
            auth(patch(ROOT + "/" + REVIEW), admin).contentType("application/json").content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REVIEW_REQUEST"));
    assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
        .isEqualTo("PENDING");
    assertThat(writer.queryForObject("SELECT version FROM app.review_item", Long.class)).isZero();
    assertThat(writer.queryForObject("SELECT review_note FROM app.review_item", String.class))
        .isNull();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.admin_audit", Long.class)).isZero();
  }

  @Test
  void serviceRejectsNullVersionWithoutHttpValidation() {
    assertThatThrownBy(
            () -> service.change(REVIEW, ADMIN, new ReviewDtos.Change(null, "IGNORED", "note")))
        .isInstanceOf(com.stelody.review.web.ReviewException.class)
        .extracting(error -> ((com.stelody.review.web.ReviewException) error).code())
        .isEqualTo("INVALID_REVIEW_REQUEST");
    assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
        .isEqualTo("PENDING");
    assertThat(writer.queryForObject("SELECT version FROM app.review_item", Long.class)).isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.admin_audit", Long.class)).isZero();
  }

  @Test
  void metadataRefreshConflictsWithAnAlreadyOpenedReviewTransaction() {
    var tx = new TransactionTemplate(transactions);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      var item = repository.findById(REVIEW).orElseThrow();
                      writer.update(
                          "UPDATE app.review_item SET source_title='new source',version=version+1 WHERE id=?",
                          REVIEW);
                      item.review("IGNORED", "stale edit", Instant.now());
                      repository.flush();
                    }))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
        .isEqualTo("PENDING");
  }

  @Test
  void invalidInputsAndMissingCandidateUseProblemResponses() throws Exception {
    mvc.perform(change(0, "PUBLISHED", "publish")).andExpect(status().isBadRequest());
    mvc.perform(change(0, "IGNORED", " ")).andExpect(status().isBadRequest());
    mvc.perform(auth(get(ROOT).param("page", "-1"), admin)).andExpect(status().isBadRequest());
    mvc.perform(auth(get(ROOT).param("size", "51"), admin)).andExpect(status().isBadRequest());
    mvc.perform(auth(get(ROOT + "/not-a-uuid"), admin)).andExpect(status().isBadRequest());
    mvc.perform(auth(get(ROOT + "/" + UUID.randomUUID()), admin)).andExpect(status().isNotFound());
  }

  @Test
  void auditFailureRollsBackDecisionAndRuntimeCannotOverwriteSource() {
    assertThatThrownBy(
            () ->
                service.change(
                    REVIEW, UUID.randomUUID(), new ReviewDtos.Change(0L, "IGNORED", "note")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
        .isEqualTo("PENDING");
    assertThat(writer.queryForObject("SELECT version FROM app.review_item", Long.class)).isZero();
    assertThatThrownBy(() -> runtime.execute("UPDATE app.review_item SET source_title='overwrite'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void expiredApiMetadataIsHiddenEvenBeforeCollectorCleanup() throws Exception {
    writer.execute(
        "UPDATE app.review_item SET source_observed_at=now()-interval '31 days',review_status='IGNORED',review_note='keep'");
    mvc.perform(auth(get(ROOT + "/" + REVIEW), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").doesNotExist())
        .andExpect(jsonPath("$.sourceExpired").value(true))
        .andExpect(jsonPath("$.reviewNote").value("keep"));
    mvc.perform(auth(get(ROOT).param("status", "IGNORED").param("disposition", "DEFERRED"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].disposition").value("DEFERRED"))
        .andExpect(jsonPath("$.items[0].sourceExpired").value(true));
    mvc.perform(auth(get(ROOT).param("status", "IGNORED").param("disposition", "REVIEW"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
  }
}
