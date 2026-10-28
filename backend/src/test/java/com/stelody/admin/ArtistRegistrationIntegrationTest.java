package com.stelody.admin;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.stelody.admin.dto.CatalogAdminDtos.ArtistInput;
import com.stelody.admin.repository.ArtistRegistrationRepository;
import com.stelody.admin.service.ArtistRegistrationService;
import com.stelody.auth.domain.SessionUser;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest(properties = "stelody.artist-registration.cleanup-delay-ms=86400000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ArtistRegistrationIntegrationTest {
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

  static final UUID ADMIN = new UUID(0, 1), OTHER = new UUID(0, 2), USER = new UUID(0, 3);
  static final String POST = "/api/v1/admin/artists", GET = "/api/v1/admin/artist-registrations/";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  @Autowired ArtistRegistrationService registrations;
  @Autowired ArtistRegistrationRepository receipts;
  @Autowired JdbcTemplate runtime;
  JdbcTemplate writer;
  Browser admin, other, user;

  record Browser(Cookie cookie, String header, String csrf) {}

  ArtistInput body() {
    return new ArtistInput(0L, "가수", List.of("Artist"), "원곡자 등록");
  }

  @BeforeEach
  void setup() throws Exception {
    writer =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    writer.execute(
        "TRUNCATE app.artist_registration_request,app.app_user,app.artist,app.catalog_audit,session.spring_session CASCADE");
    writer.update(
        "INSERT INTO app.app_user(id,google_subject,email,role) VALUES(?,'admin','admin@example.invalid','ADMIN'),(?,'other','other@example.invalid','ADMIN'),(?,'user','user@example.invalid','USER')",
        ADMIN,
        OTHER,
        USER);
    admin = browser(ADMIN);
    other = browser(OTHER);
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
    Cookie cookie =
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

  MockHttpServletRequestBuilder submit(Browser who, UUID key, ArtistInput body) {
    var request =
        post(POST)
            .cookie(who.cookie())
            .header(who.header(), who.csrf())
            .contentType("application/json")
            .content(mapper.writeValueAsString(body));
    if (key != null) request.header(ArtistRegistrationService.HEADER, key);
    return request;
  }

  JsonNode result(Browser who, UUID key) throws Exception {
    return mapper.readTree(
        mvc.perform(get(GET + key).cookie(who.cookie()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  JsonNode created(UUID key) throws Exception {
    return mapper.readTree(
        mvc.perform(submit(admin, key, body()))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  void seed(UUID key, ArtistInput body, boolean stale) throws Exception {
    String hash =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));
    writer.update(
        "INSERT INTO app.artist_registration_request(id,actor_id,body_hash,created_at) VALUES(?,?,?,clock_timestamp()-CAST(? AS interval))",
        key,
        ADMIN,
        hash,
        stale ? "3 minutes" : "0 minutes");
  }

  @Test
  void unsubmittedIdIsReadOnlyAndDoesNotPretendToBeFailedOrCompleted() throws Exception {
    UUID key = UUID.randomUUID();
    var result = result(admin, key);
    assertThat(result.path("status").asText()).isEqualTo("NOT_PROCESSED");
    for (String field :
        List.of(
            "artistId", "errorCode", "createdAt", "expiresAt", "finishedAt", "pollAfterSeconds"))
      assertThat(result.path(field).isNull()).as(field).isTrue();
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.artist_registration_request", Integer.class))
        .isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.artist", Integer.class)).isZero();
  }

  @Test
  void lostCreateResponseCanBeLookedUpAndReplayedWithoutCreatingAnotherArtist() throws Exception {
    UUID key = UUID.randomUUID();
    var first = created(key);
    var response = result(admin, key);
    assertThat(response.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(response.path("artistId")).isEqualTo(first.at("/item/id"));
    assertThat(
            Duration.between(
                Instant.parse(response.path("createdAt").asText()),
                Instant.parse(response.path("expiresAt").asText())))
        .isEqualTo(Duration.ofHours(24));
    assertThat(response.path("pollAfterSeconds").isNull()).isTrue();
    var before =
        writer.queryForMap("SELECT * FROM app.artist_registration_request WHERE id=?", key);
    // A delayed failure report after a successful commit must not replace COMPLETED.
    receipts.fail(
        key,
        ADMIN,
        before.get("body_hash").toString(),
        "ARTIST_REGISTRATION_STORAGE_UNAVAILABLE",
        503);
    assertThat(writer.queryForMap("SELECT * FROM app.artist_registration_request WHERE id=?", key))
        .isEqualTo(before);
    mvc.perform(submit(admin, key, body()))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.item.id").value(first.at("/item/id").asText()));
    assertThat(writer.queryForMap("SELECT * FROM app.artist_registration_request WHERE id=?", key))
        .isEqualTo(before);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.artist", Integer.class))
        .isEqualTo(1);
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE target_type='ARTISTS' AND action='CREATE'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void sameNameExistingArtistCannotBeMistakenForThisRequestAndReplayReturnsCurrentIdentity()
      throws Exception {
    UUID existing = UUID.randomUUID(), key = UUID.randomUUID();
    writer.update("INSERT INTO app.artist(id,name,search_name) VALUES(?,'가수','가수')", existing);
    var first = created(key);
    UUID id = UUID.fromString(first.at("/item/id").asText());
    assertThat(id).isNotEqualTo(existing);
    assertThat(result(admin, key).path("artistId").asText()).isEqualTo(id.toString());
    writer.update(
        "UPDATE app.artist SET name='수정 이름',search_name='수정 이름',version=version+1 WHERE id=?", id);
    mvc.perform(submit(admin, key, body()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.item.id").value(id.toString()))
        .andExpect(jsonPath("$.item.name").value("수정 이름"));
    assertThat(writer.queryForObject("SELECT count(*) FROM app.artist", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void differentPayloadForSameIdIsConflictAndNeverChangesOriginalReceipt() throws Exception {
    UUID key = UUID.randomUUID();
    created(key);
    var before =
        writer.queryForMap("SELECT * FROM app.artist_registration_request WHERE id=?", key);
    mvc.perform(submit(admin, key, new ArtistInput(0L, "다른 이름", List.of(), "다른 요청")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ARTIST_REGISTRATION_REQUEST_CONFLICT"));
    assertThat(writer.queryForMap("SELECT * FROM app.artist_registration_request WHERE id=?", key))
        .isEqualTo(before);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.artist", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void onlySubmittingAdminCanReadOrReplayAndSessionAndCsrfContractsRemain() throws Exception {
    UUID key = UUID.randomUUID();
    created(key);
    assertThat(result(other, key).path("status").asText()).isEqualTo("NOT_PROCESSED");
    assertThat(result(other, key).path("artistId").isNull()).isTrue();
    mvc.perform(submit(other, key, body())).andExpect(status().isNotFound());
    mvc.perform(get(GET + key)).andExpect(status().isUnauthorized());
    mvc.perform(get(GET + key).cookie(user.cookie())).andExpect(status().isForbidden());
    mvc.perform(submit(user, UUID.randomUUID(), body())).andExpect(status().isForbidden());
    mvc.perform(
            post(POST)
                .cookie(admin.cookie())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(mapper.writeValueAsString(body())))
        .andExpect(status().isForbidden());
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.artist_registration_request", Integer.class))
        .isEqualTo(1);
    var refreshed = browser(ADMIN);
    assertThat(result(refreshed, key).path("status").asText()).isEqualTo("COMPLETED");
    writer.update("UPDATE app.app_user SET role='USER' WHERE id=?", ADMIN);
    mvc.perform(get(GET + key).cookie(refreshed.cookie())).andExpect(status().isForbidden());
  }

  @Test
  void expiredOutcomeIsGoneAndTombstoneContinuesToPreventDelayedRetransmission() throws Exception {
    UUID key = UUID.randomUUID();
    created(key);
    writer.update(
        "UPDATE app.artist_registration_request SET created_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 day' WHERE id=?",
        key);
    mvc.perform(get(GET + key).cookie(admin.cookie()))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("ARTIST_REGISTRATION_RESULT_EXPIRED"));
    mvc.perform(submit(admin, key, body())).andExpect(status().isGone());
    assertThat(registrations.maintain()).isEqualTo(1);
    var row = writer.queryForMap("SELECT * FROM app.artist_registration_request WHERE id=?", key);
    assertThat(row.get("status")).isEqualTo("EXPIRED");
    for (String field : List.of("artist_id", "error_code", "error_status", "finished_at"))
      assertThat(row.get(field)).isNull();
    mvc.perform(submit(admin, key, body())).andExpect(status().isGone());
    mvc.perform(submit(admin, key, new ArtistInput(0L, "different", List.of(), "different")))
        .andExpect(status().isGone());
    assertThat(writer.queryForObject("SELECT count(*) FROM app.artist", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void validationFailureAfterAcceptanceIsRecordedAndSameRequestIsNotExecutedAgain()
      throws Exception {
    UUID key = UUID.randomUUID();
    var invalid = new ArtistInput(1L, "가수", List.of(), "검증 실패");
    mvc.perform(submit(admin, key, invalid)).andExpect(status().isBadRequest());
    var result = result(admin, key);
    assertThat(result.path("status").asText()).isEqualTo("FAILED");
    assertThat(result.path("errorCode").asText()).isEqualTo("INVALID_CATALOG_REQUEST");
    mvc.perform(submit(admin, key, invalid)).andExpect(status().isBadRequest());
    assertThat(writer.queryForObject("SELECT count(*) FROM app.artist", Integer.class)).isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class))
        .isZero();
  }

  @Test
  void outcomeCommitFailureRollsBackArtistAndAuditBeforeRecordingFailure() throws Exception {
    writer.execute(
        "CREATE FUNCTION app.fail_artist_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='COMPLETED' THEN RAISE EXCEPTION 'secret-internal-message' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
    writer.execute(
        "CREATE TRIGGER fail_artist_receipt BEFORE UPDATE ON app.artist_registration_request FOR EACH ROW EXECUTE FUNCTION app.fail_artist_receipt()");
    UUID key = UUID.randomUUID();
    try {
      mvc.perform(submit(admin, key, body()))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("CATALOG_DATA_CONFLICT"))
          .andExpect(
              content()
                  .string(
                      org.hamcrest.Matchers.not(
                          org.hamcrest.Matchers.containsString("secret-internal-message"))));
      assertThat(result(admin, key).path("status").asText()).isEqualTo("FAILED");
      assertThat(writer.queryForObject("SELECT count(*) FROM app.artist", Integer.class)).isZero();
      assertThat(writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class))
          .isZero();
    } finally {
      writer.execute("DROP TRIGGER fail_artist_receipt ON app.artist_registration_request");
      writer.execute("DROP FUNCTION app.fail_artist_receipt()");
    }
  }

  @Test
  void storageFailureIsNotReportedAsNotProcessed() throws Exception {
    UUID key = UUID.randomUUID();
    writer.execute(
        "ALTER TABLE app.artist_registration_request RENAME TO unavailable_artist_receipt");
    try {
      mvc.perform(get(GET + key).cookie(admin.cookie()))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("ARTIST_REGISTRATION_STORAGE_UNAVAILABLE"));
      mvc.perform(submit(admin, key, body())).andExpect(status().isServiceUnavailable());
    } finally {
      writer.execute(
          "ALTER TABLE app.unavailable_artist_receipt RENAME TO artist_registration_request");
    }
    assertThat(result(admin, key).path("status").asText()).isEqualTo("NOT_PROCESSED");
  }

  @Test
  void abandonedAcceptanceCanBeSafelyResumedOrEventuallyMarkedFailed() throws Exception {
    UUID resumable = UUID.randomUUID();
    seed(resumable, body(), false);
    mvc.perform(submit(admin, resumable, body())).andExpect(status().isCreated());
    UUID stale = UUID.randomUUID();
    seed(stale, body(), true);
    assertThat(registrations.maintain()).isEqualTo(1);
    assertThat(result(admin, stale).path("status").asText()).isEqualTo("FAILED");
    assertThat(result(admin, stale).path("errorCode").asText())
        .isEqualTo("ARTIST_REGISTRATION_INTERRUPTED");
    mvc.perform(submit(admin, stale, body())).andExpect(status().isServiceUnavailable());
    assertThat(writer.queryForObject("SELECT count(*) FROM app.artist", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentReplayAndMaintenanceCannotDuplicateOrFailAnActiveRegistration() throws Exception {
    UUID key = UUID.randomUUID();
    seed(key, body(), true);
    writer.execute(
        "CREATE FUNCTION app.hold_artist_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(812728001); RETURN NEW; END $$");
    writer.execute(
        "CREATE TRIGGER hold_artist_insert BEFORE INSERT ON app.artist FOR EACH ROW EXECUTE FUNCTION app.hold_artist_insert()");
    try (var blocker = Objects.requireNonNull(writer.getDataSource()).getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      blocker.createStatement().execute("SELECT pg_advisory_lock(812728001)");
      var running = executor.submit(() -> registrations.create(ADMIN, key.toString(), body()));
      try {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (writer.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=812728001 AND NOT granted",
                    Integer.class)
                == 0
            && System.nanoTime() < end) Thread.sleep(20);
        assertThat(
                writer.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=812728001 AND NOT granted",
                    Integer.class))
            .isEqualTo(1);
        var processing = result(admin, key);
        assertThat(processing.path("status").asText()).isEqualTo("PROCESSING");
        assertThat(processing.path("pollAfterSeconds").asInt()).isEqualTo(3);
        mvc.perform(submit(admin, key, body()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTIST_REGISTRATION_PROCESSING"));
        registrations.maintain();
        assertThat(result(admin, key).path("status").asText()).isEqualTo("PROCESSING");
      } finally {
        blocker.createStatement().execute("SELECT pg_advisory_unlock(812728001)");
      }
      assertThat(running.get(10, TimeUnit.SECONDS).replayed()).isFalse();
      assertThat(result(admin, key).path("status").asText()).isEqualTo("COMPLETED");
      mvc.perform(submit(admin, key, body())).andExpect(status().isOk());
    } finally {
      writer.execute("DROP TRIGGER hold_artist_insert ON app.artist");
      writer.execute("DROP FUNCTION app.hold_artist_insert()");
    }
    assertThat(writer.queryForObject("SELECT count(*) FROM app.artist", Integer.class))
        .isEqualTo(1);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void legacyRequestsAndPreAcceptanceValidationKeepExistingContracts() throws Exception {
    mvc.perform(submit(admin, null, body()))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotency-Replayed"));
    UUID key = UUID.randomUUID();
    mvc.perform(submit(admin, key, new ArtistInput(0L, " ", List.of(), "invalid")))
        .andExpect(status().isBadRequest());
    assertThat(result(admin, key).path("status").asText()).isEqualTo("NOT_PROCESSED");
    mvc.perform(submit(admin, null, body()).header("Idempotency-Key", "bad-id"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("Idempotency-Key"));
    mvc.perform(get(GET + "bad-id").cookie(admin.cookie())).andExpect(status().isBadRequest());
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.artist_registration_request", Integer.class))
        .isZero();
  }

  @Test
  void runtimeCannotRewriteOwnershipKeysOrDeleteDeduplicationTombstones() throws Exception {
    UUID key = UUID.randomUUID();
    created(key);
    for (String column : List.of("id", "actor_id", "body_hash", "created_at", "expires_at"))
      assertThat(
              runtime.queryForObject(
                  "SELECT has_column_privilege(current_user,'app.artist_registration_request',?,'UPDATE')",
                  Boolean.class,
                  column))
          .isFalse();
    assertThat(
            runtime.queryForObject(
                "SELECT has_table_privilege(current_user,'app.artist_registration_request','DELETE')",
                Boolean.class))
        .isFalse();
    assertThatThrownBy(
            () -> runtime.update("DELETE FROM app.artist_registration_request WHERE id=?", key))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
