package com.stelody;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.auth.domain.SessionUser;
import com.stelody.export.repository.ExportRepository;
import com.stelody.export.service.TokenCipher;
import com.stelody.export.service.YouTubeConnectionService;
import com.stelody.user.repository.WithdrawalRepository;
import com.stelody.user.service.WithdrawalService;
import com.stelody.user.web.AccountException;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "stelody.export.worker-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class AccountWithdrawalIntegrationTest {
  static UUID id(int n) {
    return new UUID(0, n);
  }

  static final UUID USER = id(1),
      OTHER = id(2),
      SONG = id(3),
      PLAYLIST = id(4),
      JOB = id(5),
      AUDIT = id(6),
      REVIEW = id(7);

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  static final WireMockServer google = new WireMockServer(0);

  static {
    google.start();
  }

  @AfterAll
  static void stop() {
    google.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", () -> "stelody_app");
    r.add("spring.datasource.password", () -> "test-runtime");
    r.add("spring.flyway.user", () -> "stelody_migrator");
    r.add("spring.flyway.password", () -> "test-migrator");
    r.add("YOUTUBE_TOKEN_ENCRYPTION_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
    r.add("stelody.export.revoke-uri", () -> google.baseUrl() + "/revoke");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate runtime;
  @Autowired TokenCipher cipher;
  @Autowired YouTubeConnectionService connections;
  @Autowired WithdrawalService withdrawals;
  @Autowired WithdrawalRepository proofs;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  JdbcTemplate owner;
  Browser first, another, other;

  record Browser(Cookie cookie, String csrf, String session) {}

  @BeforeEach
  void setup() throws Exception {
    google.resetAll();
    google.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/revoke"))
            .willReturn(ok()));
    owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    owner.execute(
        "TRUNCATE app.app_user,app.song_entry,app.channel,session.spring_session CASCADE");
    for (UUID user : List.of(USER, OTHER))
      owner.update(
          "INSERT INTO app.app_user(id,google_subject,email) VALUES(?,?,?)",
          user,
          user.toString(),
          user + "@example.invalid");
    owner.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES(?,'song','song','COVER','PUBLISHED')",
        SONG);
    owner.update(
        "INSERT INTO app.favorite(user_id,song_id) VALUES(?,?),(?,?)", USER, SONG, OTHER, SONG);
    owner.update(
        "INSERT INTO app.playlist(id,user_id,name,created_at,updated_at) VALUES(?,?,'private',now(),now())",
        PLAYLIST,
        USER);
    owner.update(
        "INSERT INTO app.playlist_item(id,playlist_id,song_id,position,added_at) VALUES(?,?,?,0,now())",
        id(8),
        PLAYLIST,
        SONG);
    owner.update(
        "INSERT INTO app.catalog_audit(id,target_type,target_id,actor_id,action,after_value,reason) VALUES(?,'SONG',?,?,'UPDATE','{}','test')",
        AUDIT,
        SONG,
        USER);
    owner.update(
        "INSERT INTO app.catalog_audit(id,target_type,target_id,action,after_value,reason) VALUES(?,'ACCOUNT_ROLE',?,'ROLE_GRANTED','{\"role\":\"ADMIN\"}','test')",
        id(9),
        USER);
    owner.update(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type) VALUES(?,'UC0000000000000000000000','test','GROUP')",
        id(10));
    owner.update(
        """
        INSERT INTO app.review_item(id,youtube_id,channel_id,source_observed_at,availability,disposition,
          suggested_type,rule_version,decision_reason,first_seen_at)
        VALUES(?,'00000000000',?,now(),'PUBLIC','REVIEW','UNKNOWN','test','test',now())
        """,
        REVIEW,
        id(10));
    owner.update(
        "INSERT INTO app.admin_audit(id,review_id,actor_id,before_status,after_status,after_note,changed_at) VALUES(?,?,?,'PENDING','IGNORED','test',now())",
        id(11),
        REVIEW,
        USER);
    first = browser(sessions, USER);
    another = browser(sessions, USER);
    other = browser(sessions, OTHER);
    confirm(first, USER);
  }

  <S extends Session> Browser browser(FindByIndexNameSessionRepository<S> repository, UUID user)
      throws Exception {
    S session = repository.createSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(user, Instant.now()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    repository.save(session);
    var cookie =
        new Cookie(
            "SESSION",
            Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    var result =
        mvc.perform(get("/api/v1/auth/csrf").cookie(cookie))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return new Browser(
        cookie,
        mapper.readTree(result.getContentAsString()).path("token").asText(),
        session.getId());
  }

  void confirm(Browser browser, UUID user) {
    // OAuth validation is covered separately by GoogleLoginIntegrationTest. This fixture isolates
    // deletion.
    owner.update(
        "INSERT INTO app.account_reauthentication(user_id,session_hash,generation,status,expires_at) VALUES(?,?,?,'CONFIRMED',?)",
        user,
        YouTubeConnectionService.hash(browser.session()),
        UUID.randomUUID(),
        Timestamp.from(Instant.now().plusSeconds(300)));
  }

  void connected() {
    runtime.update(
        "INSERT INTO app.youtube_connection(user_id,generation,status,access_token,refresh_token,expires_at) VALUES(?,?,'CONNECTED',?,?,?)",
        USER,
        UUID.randomUUID(),
        cipher.encrypt(USER, "access", "test-access"),
        cipher.encrypt(USER, "refresh", "test-refresh"),
        Timestamp.from(Instant.now().plusSeconds(3600)));
    runtime.update(
        "INSERT INTO app.youtube_authorization(user_id,generation,state_hash,session_hash,verifier,nonce,expires_at) VALUES(?,?,?,?,?,?,?)",
        USER,
        UUID.randomUUID(),
        YouTubeConnectionService.hash("test-state"),
        YouTubeConnectionService.hash(first.session()),
        cipher.encrypt(USER, "verifier", "test-verifier"),
        "nonce",
        Timestamp.from(Instant.now().plusSeconds(300)));
    runtime.update(
        "INSERT INTO app.youtube_export(id,user_id,request_id,source_playlist_id,source_version,name,status) VALUES(?,?,?,?,0,'test','QUEUED')",
        JOB,
        USER,
        UUID.randomUUID(),
        PLAYLIST);
    runtime.update(
        "INSERT INTO app.youtube_export_item(export_id,position,song_id,youtube_id,status) VALUES(?,0,?,'00000000000','PENDING')",
        JOB,
        SONG);
  }

  void delete(Browser browser, int status, String code) throws Exception {
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/me")
                    .cookie(browser.cookie())
                    .header("X-CSRF-TOKEN", browser.csrf()))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse();
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    if (code != null) assertThat(response.getContentAsString()).contains(code);
  }

  int count(String table, String column, UUID value) {
    // The test supplies only constant table/column names, never request data.
    return owner.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class, value);
  }

  void retained() throws Exception {
    assertThat(count("app.app_user", "id", USER)).isEqualTo(1);
    assertThat(count("app.favorite", "user_id", USER)).isEqualTo(1);
    assertThat(count("app.playlist_item", "playlist_id", PLAYLIST)).isEqualTo(1);
    assertThat(sessions.findByPrincipalName(USER.toString())).hasSize(2);
    mvc.perform(get("/api/v1/me").cookie(first.cookie())).andExpect(status().isOk());
  }

  @Test
  void deletesOnlyOwnDataAndAnonymizesAuditWhilePreservingPublicCatalog() throws Exception {
    connected();
    delete(first, 204, null);
    for (String table :
        List.of(
            "app.app_user",
            "app.favorite",
            "app.playlist",
            "app.youtube_connection",
            "app.youtube_authorization",
            "app.youtube_export",
            "app.account_reauthentication"))
      assertThat(count(table, table.equals("app.app_user") ? "id" : "user_id", USER)).isZero();
    assertThat(count("app.playlist_item", "playlist_id", PLAYLIST)).isZero();
    assertThat(count("app.youtube_export_item", "export_id", JOB)).isZero();
    assertThat(count("app.catalog_audit", "actor_id", USER)).isZero();
    assertThat(count("app.admin_audit", "actor_id", USER)).isZero();
    assertThat(
            owner.queryForObject(
                "SELECT target_id FROM app.catalog_audit WHERE id=?", UUID.class, id(9)))
        .isNotEqualTo(USER);
    assertThat(count("app.catalog_audit", "target_id", SONG)).isEqualTo(1);
    assertThat(count("app.song_entry", "id", SONG)).isEqualTo(1);
    assertThat(count("app.review_item", "id", REVIEW)).isEqualTo(1);
    assertThat(count("app.favorite", "user_id", OTHER)).isEqualTo(1);
    assertThat(sessions.findByPrincipalName(USER.toString())).isEmpty();
    assertThat(sessions.findByPrincipalName(OTHER.toString())).hasSize(1);
    mvc.perform(get("/api/v1/me").cookie(another.cookie())).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me").cookie(other.cookie())).andExpect(status().isOk());
    google.verify(
        1, postRequestedFor(urlEqualTo("/revoke")).withRequestBody(equalTo("token=test-refresh")));
  }

  @Test
  void proofCannotBeUsedFromAnotherSessionOrForAnotherUser() throws Exception {
    delete(another, 409, "REAUTHENTICATION_REQUIRED");
    delete(other, 409, "REAUTHENTICATION_REQUIRED");
    retained();
    assertThat(
            runtime.queryForObject(
                "SELECT app.withdraw_account(?,?)",
                Boolean.class,
                OTHER,
                YouTubeConnectionService.hash(first.session())))
        .isFalse();
  }

  @Test
  void expiredProofBlocksDeletionAndCleanupKeepsValidProofs() throws Exception {
    confirm(other, OTHER);
    owner.update(
        "UPDATE app.account_reauthentication SET expires_at=clock_timestamp()-interval '1 second' WHERE user_id=?",
        USER);
    delete(first, 409, "REAUTHENTICATION_REQUIRED");
    retained();
    assertThat(proofs.expire()).isEqualTo(1);
    assertThat(count("app.account_reauthentication", "user_id", USER)).isZero();
    assertThat(count("app.account_reauthentication", "user_id", OTHER)).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 500})
  void failedRevocationCommitsPendingStateAndCanRetryWithoutLosingData(int remoteStatus)
      throws Exception {
    connected();
    google.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/revoke"))
            .willReturn(
                aResponse()
                    .withStatus(remoteStatus)
                    .withBody("{\"error\":\"temporarily_unavailable\"}")));
    delete(first, 409, "YOUTUBE_REVOCATION_PENDING");
    retained();
    assertThat(
            runtime.queryForObject(
                "SELECT status FROM app.youtube_connection WHERE user_id=?", String.class, USER))
        .isEqualTo("REVOKING");
    assertThat(
            runtime.queryForObject(
                "SELECT status FROM app.youtube_export WHERE id=?", String.class, JOB))
        .isEqualTo("CANCELLED");
    assertThat(count("app.youtube_authorization", "user_id", USER)).isZero();
    assertThat(count("app.account_reauthentication", "user_id", USER)).isEqualTo(1);
    google.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/revoke"))
            .willReturn(ok()));
    delete(first, 204, null);
    google.verify(2, postRequestedFor(urlEqualTo("/revoke")));
  }

  @Test
  void alreadyInvalidGoogleTokenAllowsWithdrawal() throws Exception {
    connected();
    google.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/revoke"))
            .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid_token\"}")));
    delete(first, 204, null);
    assertThat(count("app.app_user", "id", USER)).isZero();
  }

  @Test
  void activeExportLockBlocksRevocationAndDeletionUntilWorkerFinishes() throws Exception {
    connected();
    try (var connection =
            new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
                .getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SELECT pg_advisory_lock(" + ExportRepository.PROCESS_LOCK + ")");
      delete(first, 409, "YOUTUBE_EXPORT_BUSY");
      retained();
      google.verify(0, postRequestedFor(urlEqualTo("/revoke")));
      assertThat(
              runtime.queryForObject(
                  "SELECT status FROM app.youtube_export WHERE id=?", String.class, JOB))
          .isEqualTo("QUEUED");
      statement.execute("SELECT pg_advisory_unlock(" + ExportRepository.PROCESS_LOCK + ")");
    }
    delete(first, 204, null);
  }

  @Test
  void runtimeCannotBypassFunctionGuardsOrEditImmutableAudits() {
    assertThatThrownBy(() -> runtime.update("DELETE FROM app.app_user WHERE id=?", USER))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> runtime.update("UPDATE app.catalog_audit SET reason='changed' WHERE id=?", AUDIT))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(
            runtime.queryForObject(
                "SELECT app.withdraw_account(?,?)", Boolean.class, USER, "wrong-session"))
        .isFalse();
    connected();
    assertThat(
            runtime.queryForObject(
                "SELECT app.withdraw_account(?,?)",
                Boolean.class,
                USER,
                YouTubeConnectionService.hash(first.session())))
        .isFalse();
    for (var role :
        Map.of("stelody_collector", "test-collector", "stelody_operator", "test-operator")
            .entrySet()) {
      var otherRole =
          new JdbcTemplate(
              new DriverManagerDataSource(postgres.getJdbcUrl(), role.getKey(), role.getValue()));
      assertThatThrownBy(
              () ->
                  otherRole.queryForObject(
                      "SELECT app.withdraw_account(?,?)",
                      Boolean.class,
                      USER,
                      YouTubeConnectionService.hash(first.session())))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    assertThat(
            owner.queryForObject(
                "SELECT prosecdef FROM pg_proc WHERE oid='app.withdraw_account(uuid,text)'::regprocedure",
                Boolean.class))
        .isTrue();
  }

  @Test
  void databaseFailureRollsBackLocalDeletionAndCanRecoverAfterRemoteRevocation() throws Exception {
    connected();
    owner.execute(
        "CREATE FUNCTION app.fail_withdrawal() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private-storage-detail'; END $$");
    owner.execute(
        "CREATE TRIGGER fail_withdrawal BEFORE DELETE ON app.app_user FOR EACH ROW EXECUTE FUNCTION app.fail_withdrawal()");
    try {
      delete(first, 503, "ACCOUNT_STORAGE_UNAVAILABLE");
      retained();
      assertThat(count("app.catalog_audit", "actor_id", USER)).isEqualTo(1);
      assertThat(
              owner.queryForObject(
                  "SELECT target_id FROM app.catalog_audit WHERE id=?", UUID.class, id(9)))
          .isEqualTo(USER);
      assertThat(
              runtime.queryForObject(
                  "SELECT status FROM app.youtube_connection WHERE user_id=?", String.class, USER))
          .isEqualTo("CONNECTED");
    } finally {
      owner.execute("DROP TRIGGER fail_withdrawal ON app.app_user");
      owner.execute("DROP FUNCTION app.fail_withdrawal()");
    }
    google.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/revoke"))
            .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid_token\"}")));
    delete(first, 204, null);
  }

  @Test
  void concurrentWithdrawalsHaveExactlyOneSuccess() {
    var a = CompletableFuture.supplyAsync(() -> withdrawResult());
    var b = CompletableFuture.supplyAsync(() -> withdrawResult());
    assertThat(List.of(a.join(), b.join()))
        .allSatisfy(result -> assertThat(result).isIn(204, 401, 409))
        .containsOnlyOnce(204);
    assertThat(count("app.app_user", "id", USER)).isZero();
  }

  int withdrawResult() {
    try {
      withdrawals.withdraw(USER, first.session());
      return 204;
    } catch (AccountException e) {
      return e.status();
    }
  }
}
