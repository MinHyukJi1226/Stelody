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

  @Test
  void completingSnapshotDeletesOnlyUnregisteredSnapshotCandidatesAndTheirAudit() throws Exception {
    var original = service.completionPreview();
    UUID registered = new UUID(0, 31), fresh = new UUID(0, 32);
    seed(registered, 31);
    var batch = service.completionPreview();
    writer.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES(?,'kept','kept','COVER','DRAFT')",
        registered);
    writer.update(
        "INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title,source_observed_at,status_observed_at) VALUES(?,?,?,?,'OFFICIAL_COVER','PUBLIC','kept',now(),now())",
        registered,
        registered,
        "YT%09d".formatted(31),
        CHANNEL);
    writer.update(
        "UPDATE app.review_item SET review_status='REGISTERED',registered_video_id=?,version=version+1 WHERE id=?",
        registered,
        registered);
    seed(fresh, 32);
    writer.update(
        "INSERT INTO app.admin_audit(id,review_id,before_status,after_status,after_note,changed_at) VALUES(?,?,'PENDING','PENDING','reviewed',now())",
        UUID.randomUUID(),
        REVIEW);
    String body = mapper.writeValueAsString(new ReviewDtos.Complete(batch.items()));
    for (int deleted : List.of(1, 0))
      mvc.perform(
              auth(post(ROOT + "/completion"), admin).contentType("application/json").content(body))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.deletedCount").value(deleted));
    assertThat(writer.queryForList("SELECT id FROM app.review_item", UUID.class))
        .containsExactlyInAnyOrder(registered, fresh);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.admin_audit", Integer.class))
        .isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Integer.class))
        .isEqualTo(1);
    assertThat(original.items()).extracting(ReviewDtos.Candidate::id).containsExactly(REVIEW);
  }

  @Test
  void completionPreviewExcludesDeferredIgnoredExcludedAndExpiredCandidates() throws Exception {
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
    assertThat(
            service
                .complete(ADMIN, new ReviewDtos.Complete(service.completionPreview().items()))
                .deletedCount())
        .isEqualTo(1);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.review_item", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void changedCandidateRejectsWholeCompletionWithoutDeletingOtherCandidates() throws Exception {
    seed(new UUID(0, 5), 5);
    var batch = service.completionPreview();
    writer.update("UPDATE app.review_item SET version=version+1 WHERE id=?", REVIEW);
    mvc.perform(
            auth(post(ROOT + "/completion"), admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(new ReviewDtos.Complete(batch.items()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_VERSION_CONFLICT"));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.review_item", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void completionRequiresAdminCsrfValidUniqueSnapshotAndRetainsRestrictedDeletePrivileges()
      throws Exception {
    String body =
        mapper.writeValueAsString(new ReviewDtos.Complete(service.completionPreview().items()));
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
            "{\"items\":[null]}",
            "{\"items\":[{\"id\":\"bad\",\"version\":0}]}",
            mapper.writeValueAsString(
                new ReviewDtos.Complete(
                    List.of(
                        new ReviewDtos.Candidate(REVIEW, 0L),
                        new ReviewDtos.Candidate(REVIEW, 0L))))))
      mvc.perform(
              auth(post(ROOT + "/completion"), admin)
                  .contentType("application/json")
                  .content(invalid))
          .andExpect(status().isBadRequest());
    assertThatThrownBy(() -> runtime.execute("DELETE FROM app.review_item"))
        .hasRootCauseMessage("ERROR: permission denied for table review_item");
    assertThatThrownBy(() -> runtime.execute("SELECT app.cleanup_discovery_reviews(NULL,NULL)"))
        .hasRootCauseMessage("ERROR: permission denied for function cleanup_discovery_reviews");
    writer.update("UPDATE app.app_user SET role='USER' WHERE id=?", ADMIN);
    assertThat(
            runtime.queryForObject(
                "SELECT app.complete_review_candidates('[]'::jsonb,?::uuid)", Integer.class, ADMIN))
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
