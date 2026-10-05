package com.stelody.export;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.stelody.auth.domain.SessionUser;
import com.stelody.export.config.ExportSettings;
import com.stelody.export.repository.ExportRepository;
import com.stelody.export.service.*;
import com.stelody.playlist.service.*;
import jakarta.servlet.http.Cookie;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest(
    properties = {
      "stelody.export.enabled=true",
      "stelody.export.worker-enabled=false",
      "stelody.export.redirect-uri=http://localhost:8080/api/v1/me/youtube/callback"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(YouTubeExportIntegrationTest.Provider.class)
@Testcontainers
@Tag("integration")
class YouTubeExportIntegrationTest {
  static UUID id(int n) {
    return new UUID(0, n);
  }

  static final UUID U1 = id(1), U2 = id(2), S1 = id(101), S2 = id(102), S3 = id(103), M1 = id(10);
  static final String CONNECTION = "/api/v1/me/youtube/connection",
      AUTH = "/api/v1/me/youtube/authorizations",
      CALLBACK = "/api/v1/me/youtube/callback",
      JOBS = "/api/v1/me/youtube-exports/";

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  static final WireMockServer provider = new WireMockServer(0);
  static final RSAKey key;

  static {
    try {
      key = new RSAKeyGenerator(2048).keyID("export-key").generate();
      provider.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @AfterAll
  static void stop() {
    provider.stop();
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", () -> "stelody_app");
    r.add("spring.datasource.password", () -> "test-runtime");
    r.add("spring.flyway.user", () -> "stelody_migrator");
    r.add("spring.flyway.password", () -> "test-migrator");
    r.add("YOUTUBE_TOKEN_ENCRYPTION_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
    r.add("stelody.export.api-url", () -> provider.baseUrl() + "/youtube/v3");
    r.add("stelody.export.revoke-uri", () -> provider.baseUrl() + "/revoke");
  }

  @TestConfiguration
  static class Provider {
    @Bean
    ClientRegistrationRepository clients() {
      return new InMemoryClientRegistrationRepository(
          CommonOAuth2Provider.GOOGLE
              .getBuilder("google")
              .clientId("export-client")
              .clientSecret("export-secret")
              .scope("openid", "email")
              .issuerUri("https://accounts.google.com")
              .authorizationUri(provider.baseUrl() + "/authorize")
              .tokenUri(provider.baseUrl() + "/token")
              .jwkSetUri(provider.baseUrl() + "/jwks")
              .redirectUri("{baseUrl}/api/v1/auth/callback/google")
              .build());
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate runtime;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  @Autowired PlaylistService playlists;
  @Autowired PlaylistItemService playlistItems;
  @Autowired ExportWorker worker;
  @Autowired TokenCipher cipher;
  @Autowired ExportRepository store;
  @Autowired YouTubeConnectionService connections;
  JdbcTemplate owner;
  Browser first, second;

  record Browser(Cookie cookie, String csrf, String sessionId) {}

  record Flow(String state, String nonce, String challenge) {}

  @BeforeEach
  void setup() throws Exception {
    provider.resetAll();
    provider.stubFor(
        WireMock.get(urlEqualTo("/jwks"))
            .willReturn(okJson(new JWKSet(key.toPublicJWK()).toString())));
    provider.stubFor(WireMock.post(urlEqualTo("/revoke")).willReturn(ok()));
    provider.stubFor(
        WireMock.post(urlPathEqualTo("/youtube/v3/playlists"))
            .willReturn(okJson("{\"id\":\"PL_export\"}")));
    provider.stubFor(
        WireMock.post(urlPathEqualTo("/youtube/v3/playlistItems"))
            .willReturn(okJson("{\"id\":\"item\"}")));
    remote(List.of());
    publicVideos(List.of(video(S1), video(S2), video(S3)));
    owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    owner.execute("TRUNCATE app.app_user,app.member,app.song_entry,session.spring_session CASCADE");
    for (UUID u : List.of(U1, U2))
      owner.update(
          "INSERT INTO app.app_user(id,google_subject,email) VALUES(?,?,?)",
          u,
          u.toString(),
          u + "@example.invalid");
    owner.update(
        "INSERT INTO app.member(id,name,search_name,activity_status) VALUES(?,'test','test','ACTIVE')",
        M1);
    for (UUID s : List.of(S1, S2, S3)) {
      owner.update(
          "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES(?,'test','test','COVER','PUBLISHED')",
          s);
      UUID v = id(s.hashCode() + 1000);
      owner.update(
          "INSERT INTO app.video(id,song_id,youtube_id,video_kind,availability,source_title,published_at) VALUES(?,?,?,'OFFICIAL_COVER','PUBLIC','test','2026-01-01')",
          v,
          s,
          video(s));
      owner.update("UPDATE app.song_entry SET representative_video_id=? WHERE id=?", v, s);
      owner.update("INSERT INTO app.song_member(song_id,member_id,position) VALUES(?,?,0)", s, M1);
    }
    first = browser(U1);
    second = browser(U2);
  }

  String video(UUID song) {
    return "%011d".formatted(song.hashCode());
  }

  Browser browser(UUID user) throws Exception {
    return browser(sessions, user);
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
    var csrf = json(get("/api/v1/auth/csrf").cookie(cookie), null, 200).path("token").asText();
    return new Browser(cookie, csrf, session.getId());
  }

  JsonNode json(MockHttpServletRequestBuilder request, Browser browser, int status)
      throws Exception {
    if (browser != null) request.cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf());
    var response =
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse();
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    if (status == 303) {
      assertThat(response.getHeader("Location")).isEqualTo(CONNECTION);
      assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
      return json(get(CONNECTION), browser, 200);
    }
    return mapper.readTree(response.getContentAsString());
  }

  void connected(UUID user) {
    runtime.update(
        "INSERT INTO app.youtube_connection(user_id,generation,status,access_token,refresh_token,expires_at) VALUES(?,?,'CONNECTED',?,?,?)",
        user,
        UUID.randomUUID(),
        cipher.encrypt(user, "access", "test-access"),
        cipher.encrypt(user, "refresh", "test-refresh"),
        Timestamp.from(Instant.now().plusSeconds(3600)));
  }

  UUID playlist(UUID... songs) {
    var p = playlists.create(U1, "Export title");
    long version = p.version();
    for (UUID song : songs)
      version = playlistItems.add(U1, p.id(), version, song).playlist().version();
    return p.id();
  }

  JsonNode create(UUID playlist, UUID request) throws Exception {
    long version = playlists.detail(U1, playlist).version();
    return json(
        post("/api/v1/me/playlists/" + playlist + "/youtube-exports")
            .content("{\"requestId\":\"" + request + "\",\"version\":" + version + "}"),
        first,
        202);
  }

  JsonNode job(UUID id) throws Exception {
    return json(get(JOBS + id), first, 200);
  }

  Flow flow(Browser browser) throws Exception {
    String url = json(post(AUTH), browser, 200).path("authorizationUrl").asText();
    Map<String, String> params = new HashMap<>();
    for (String entry : URI.create(url).getRawQuery().split("&")) {
      String[] pair = entry.split("=", 2);
      params.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    assertThat(params.get("scope")).contains(ExportSettings.SCOPE);
    assertThat(params.get("access_type")).isEqualTo("offline");
    assertThat(params.get("code_challenge_method")).isEqualTo("S256");
    return new Flow(params.get("state"), params.get("nonce"), params.get("code_challenge"));
  }

  String identity(String subject, String nonce, String audience, String issuer, Instant expiry)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .audience(audience)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(expiry))
            .claim("nonce", nonce)
            .build();
    var token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    token.sign(new RSASSASigner(key));
    return token.serialize();
  }

  void token(String idToken, String scope) {
    provider.stubFor(
        WireMock.post(urlEqualTo("/token"))
            .willReturn(
                okJson(
                    mapper.writeValueAsString(
                        Map.of(
                            "access_token",
                            "issued-access",
                            "token_type",
                            "Bearer",
                            "expires_in",
                            3600,
                            "refresh_token",
                            "issued-refresh",
                            "scope",
                            scope,
                            "id_token",
                            idToken)))));
  }

  void remote(List<String> videos) {
    var items = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < videos.size(); i++)
      items.add(
          Map.of("snippet", Map.of("position", i, "resourceId", Map.of("videoId", videos.get(i)))));
    provider.stubFor(
        WireMock.get(urlPathEqualTo("/youtube/v3/playlistItems"))
            .willReturn(okJson(mapper.writeValueAsString(Map.of("items", items)))));
  }

  void publicVideos(List<String> ids) {
    var items =
        ids.stream()
            .map(
                id ->
                    Map.of(
                        "id",
                        id,
                        "status",
                        Map.of("privacyStatus", "public", "uploadStatus", "processed")))
            .toList();
    provider.stubFor(
        WireMock.get(urlPathEqualTo("/youtube/v3/videos"))
            .willReturn(okJson(mapper.writeValueAsString(Map.of("items", items)))));
  }

  @Test
  void consentIsSessionBoundSingleUseAndEncryptedWithoutReplacingLogin() throws Exception {
    Flow flow = flow(first);
    token(
        identity(
            U1.toString(),
            flow.nonce(),
            "export-client",
            "https://accounts.google.com",
            Instant.now().plusSeconds(600)),
        "openid email " + ExportSettings.SCOPE);
    assertThat(
            json(get(CALLBACK).param("state", flow.state()).param("code", "test-code"), second, 400)
                .path("code")
                .asText())
        .isEqualTo("INVALID_YOUTUBE_STATE");
    assertThat(
            json(get(CALLBACK).param("state", flow.state()).param("code", "test-code"), first, 303)
                .path("status")
                .asText())
        .isEqualTo("CONNECTED");
    assertThat(json(get("/api/v1/me"), first, 200).path("id").asText()).isEqualTo(U1.toString());
    json(get(CALLBACK).param("state", flow.state()).param("code", "test-code"), first, 400);
    String encrypted =
        runtime.queryForObject(
            "SELECT refresh_token FROM app.youtube_connection WHERE user_id=?", String.class, U1);
    assertThat(encrypted).doesNotContain("issued-refresh");
    assertThat(cipher.decrypt(U1, "refresh", encrypted)).isEqualTo("issued-refresh");
    provider.verify(
        1, postRequestedFor(urlEqualTo("/token")).withRequestBody(containing("code_verifier=")));
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.youtube_authorization", Long.class))
        .isZero();
  }

  @Test
  void reauthorizationSurvivesCleanupOfPreviouslyStaleConnection() throws Exception {
    connected(U1);
    owner.update(
        "UPDATE app.youtube_connection SET updated_at=now()-interval '31 days' WHERE user_id=?",
        U1);
    Flow flow = flow(first);
    UUID generation =
        runtime.queryForObject(
            "SELECT generation FROM app.youtube_connection WHERE user_id=?", UUID.class, U1);
    token(
        identity(
            U1.toString(),
            flow.nonce(),
            "export-client",
            "https://accounts.google.com",
            Instant.now().plusSeconds(600)),
        "openid email " + ExportSettings.SCOPE);

    worker.tick();

    assertThat(connections.status(U1).status()).isEqualTo("CONNECTED");
    assertThat(
            runtime.queryForObject(
                "SELECT generation FROM app.youtube_connection WHERE user_id=?", UUID.class, U1))
        .isEqualTo(generation);
    provider.verify(0, postRequestedFor(urlEqualTo("/revoke")));
    assertThat(
            json(get(CALLBACK).param("state", flow.state()).param("code", "test-code"), first, 303)
                .path("status")
                .asText())
        .isEqualTo("CONNECTED");
    String encrypted =
        runtime.queryForObject(
            "SELECT refresh_token FROM app.youtube_connection WHERE user_id=?", String.class, U1);
    assertThat(cipher.decrypt(U1, "refresh", encrypted)).isEqualTo("issued-refresh");
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.youtube_authorization", Long.class))
        .isZero();
    provider.verify(1, postRequestedFor(urlEqualTo("/token")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"subject", "nonce", "audience", "issuer", "expired", "scope"})
  void rejectsWrongIdentityAndPartialConsent(String fault) throws Exception {
    Flow flow = flow(first);
    token(
        identity(
            fault.equals("subject") ? U2.toString() : U1.toString(),
            fault.equals("nonce") ? "wrong" : flow.nonce(),
            fault.equals("audience") ? "other-client" : "export-client",
            fault.equals("issuer") ? "https://example.invalid" : "https://accounts.google.com",
            fault.equals("expired")
                ? Instant.now().minusSeconds(120)
                : Instant.now().plusSeconds(600)),
        fault.equals("scope") ? "openid email" : "openid email " + ExportSettings.SCOPE);
    int status = List.of("audience", "issuer", "expired").contains(fault) ? 502 : 400;
    json(get(CALLBACK).param("state", flow.state()).param("code", "test-code"), first, status);
    assertThat(connections.status(U1).status()).isEqualTo("DISCONNECTED");
  }

  @Test
  void denialExpiryAndDisconnectCannotCompleteStaleAuthorization() throws Exception {
    Flow denied = flow(first);
    json(get(CALLBACK).param("state", denied.state()).param("error", "access_denied"), first, 400);
    Flow expired = flow(first);
    owner.execute("UPDATE app.youtube_authorization SET expires_at=now()-interval '1 second'");
    json(get(CALLBACK).param("state", expired.state()).param("code", "test-code"), first, 400);
    Flow stale = flow(first);
    json(delete(CONNECTION), first, 200);
    json(get(CALLBACK).param("state", stale.state()).param("code", "test-code"), first, 400);
    provider.verify(0, postRequestedFor(urlEqualTo("/token")));
    assertThat(json(get("/api/v1/me"), first, 200).path("id").asText()).isEqualTo(U1.toString());
  }

  @Test
  void privateCopyPreservesSnapshotOrderSkipsUnavailableAndIsIdempotent() throws Exception {
    connected(U1);
    UUID playlist = playlist(S1, S2, S3);
    owner.update("UPDATE app.song_entry SET visibility='HIDDEN' WHERE id=?", S2);
    UUID request = UUID.randomUUID();
    var created = create(playlist, request);
    UUID id = UUID.fromString(created.path("id").asText());
    assertThat(create(playlist, request).path("id").asText()).isEqualTo(id.toString());
    playlists.rename(
        U1, playlist, playlists.detail(U1, playlist).version(), "Changed after request");
    worker.tick();
    var done = job(id);
    assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(done.path("copiedCount").asInt()).isEqualTo(2);
    assertThat(done.path("skippedCount").asInt()).isEqualTo(1);
    assertThat(done.path("remainingCount").asInt()).isZero();
    assertThat(done.path("youtubeUrl").asText())
        .isEqualTo("https://www.youtube.com/playlist?list=PL_export");
    provider.verify(
        1,
        postRequestedFor(urlPathEqualTo("/youtube/v3/playlists"))
            .withRequestBody(matchingJsonPath("$.status.privacyStatus", equalTo("private")))
            .withRequestBody(matchingJsonPath("$.snippet.title", equalTo("Export title"))));
    provider.verify(
        1,
        postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems"))
            .withRequestBody(matchingJsonPath("$.snippet.resourceId.videoId", equalTo(video(S1))))
            .withRequestBody(matchingJsonPath("$.snippet.position", equalTo("0"))));
    provider.verify(
        1,
        postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems"))
            .withRequestBody(matchingJsonPath("$.snippet.resourceId.videoId", equalTo(video(S3))))
            .withRequestBody(matchingJsonPath("$.snippet.position", equalTo("1"))));
    json(get(JOBS + id), second, 404);
    json(post(JOBS + id + "/retry"), second, 404);
  }

  @Test
  void newlyCreatedPlaylistRead404IsRetriedWithoutRepeatingCreation() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1, S2), UUID.randomUUID()).path("id").asText());
    for (int attempt = 0; attempt < 3; attempt++) {
      provider.stubFor(
          WireMock.get(urlPathEqualTo("/youtube/v3/playlistItems"))
              .withQueryParam("playlistId", equalTo("PL_export"))
              .atPriority(1)
              .inScenario("playlist-visible-after-create")
              .whenScenarioStateIs(attempt == 0 ? "Started" : "read-" + attempt)
              .willSetStateTo("read-" + (attempt + 1))
              .willReturn(
                  attempt < 2
                      ? aResponse()
                          .withStatus(404)
                          .withBody("{\"error\":{\"errors\":[{\"reason\":\"playlistNotFound\"}]}}")
                      : okJson("{\"items\":[]}")));
    }
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(job(id).path("copiedCount").asInt()).isEqualTo(2);
    provider.verify(3, getRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
    provider.verify(1, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
    provider.verify(2, postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
  }

  @Test
  void persistentRead404StopsAfterThreeReadsAndLaterResumesSamePlaylist() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1, S2), UUID.randomUUID()).path("id").asText());
    provider.stubFor(
        WireMock.get(urlPathEqualTo("/youtube/v3/playlistItems"))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"playlistNotFound\"}]}}")));
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("FAILED");
    assertThat(job(id).path("errorCode").asText()).isEqualTo("REMOTE_PLAYLIST_MISSING");
    assertThat(job(id).path("copiedCount").asInt()).isZero();
    provider.verify(3, getRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
    provider.verify(0, postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
    provider.stubFor(
        WireMock.get(urlPathEqualTo("/youtube/v3/playlistItems"))
            .atPriority(1)
            .willReturn(okJson("{\"items\":[]}")));
    json(post(JOBS + id + "/retry"), first, 200);
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(job(id).path("youtubePlaylistId").asText()).isEqualTo("PL_export");
    provider.verify(1, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
  }

  @Test
  void playlistReadQuotaFailureDoesNotRetryOrWriteItems() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    provider.stubFor(
        WireMock.get(urlPathEqualTo("/youtube/v3/playlistItems"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}")));
    worker.tick();
    assertThat(job(id).path("errorCode").asText()).isEqualTo("YOUTUBE_QUOTA_EXCEEDED");
    provider.verify(1, getRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
    provider.verify(0, postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
  }

  @Test
  void quotaFailureResumesExistingPlaylistWithoutDuplicatingCopiedItems() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1, S2), UUID.randomUUID()).path("id").asText());
    provider.stubFor(
        WireMock.post(urlPathEqualTo("/youtube/v3/playlistItems"))
            .withRequestBody(matchingJsonPath("$.snippet.resourceId.videoId", equalTo(video(S2))))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}")));
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("FAILED");
    assertThat(job(id).path("copiedCount").asInt()).isEqualTo(1);
    assertThat(job(id).path("errorCode").asText()).isEqualTo("YOUTUBE_QUOTA_EXCEEDED");
    remote(List.of(video(S1)));
    provider.stubFor(
        WireMock.post(urlPathEqualTo("/youtube/v3/playlistItems"))
            .withRequestBody(matchingJsonPath("$.snippet.resourceId.videoId", equalTo(video(S2))))
            .willReturn(okJson("{\"id\":\"item2\"}")));
    json(post(JOBS + id + "/retry"), first, 200);
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("SUCCEEDED");
    provider.verify(1, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
    provider.verify(
        1,
        postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems"))
            .withRequestBody(matchingJsonPath("$.snippet.resourceId.videoId", equalTo(video(S1)))));
  }

  @Test
  void uncertainInsertIsReadBackBeforeRetryAndNeverBlindlyRepeated() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    provider.stubFor(
        WireMock.post(urlPathEqualTo("/youtube/v3/playlistItems"))
            .willReturn(aResponse().withStatus(503)));
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("UNCERTAIN");
    json(post(JOBS + id + "/retry"), first, 200);
    worker.tick();
    assertThat(job(id).path("errorCode").asText()).isEqualTo("YOUTUBE_RESULT_UNCONFIRMED");
    provider.verify(1, postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
    remote(List.of(video(S1)));
    json(post(JOBS + id + "/retry"), first, 200);
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(job(id).path("copiedCount").asInt()).isEqualTo(1);
    provider.verify(1, postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
  }

  @Test
  void uncertainCreationRecoversMarkerAndDoesNotCreateAnotherPlaylist() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    provider.stubFor(
        WireMock.post(urlPathEqualTo("/youtube/v3/playlists"))
            .willReturn(aResponse().withStatus(503)));
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("UNCERTAIN");
    provider.stubFor(
        WireMock.get(urlPathEqualTo("/youtube/v3/playlists"))
            .willReturn(
                okJson(
                    mapper.writeValueAsString(
                        Map.of(
                            "items",
                            List.of(
                                Map.of(
                                    "id",
                                    "PL_export",
                                    "snippet",
                                    Map.of("description", "Stelody export " + id),
                                    "status",
                                    Map.of("privacyStatus", "private"))))))));
    json(post(JOBS + id + "/retry"), first, 200);
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("SUCCEEDED");
    provider.verify(1, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
  }

  @Test
  void restartWithPendingMutationReconcilesAndRemoteEditStopsFurtherWrites() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1, S2), UUID.randomUUID()).path("id").asText());
    owner.update(
        "UPDATE app.youtube_export SET status='RUNNING',youtube_playlist_id='PL_export',in_flight=true,in_flight_position=0 WHERE id=?",
        id);
    remote(List.of(video(S1)));
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("SUCCEEDED");
    provider.verify(0, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
    provider.verify(
        0,
        postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems"))
            .withRequestBody(matchingJsonPath("$.snippet.resourceId.videoId", equalTo(video(S1)))));
    UUID next = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    remote(List.of(video(S3)));
    worker.tick();
    assertThat(job(next).path("errorCode").asText()).isEqualTo("REMOTE_PLAYLIST_CHANGED");
  }

  @Test
  void refreshRotationAndInvalidGrantAreStoredWithoutTokenExposure() throws Exception {
    connected(U1);
    owner.execute("UPDATE app.youtube_connection SET expires_at=now()-interval '1 minute'");
    provider.stubFor(
        WireMock.post(urlEqualTo("/token"))
            .withRequestBody(containing("grant_type=refresh_token"))
            .willReturn(
                okJson(
                    "{\"access_token\":\"rotated-access\",\"refresh_token\":\"rotated-refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\""
                        + ExportSettings.SCOPE
                        + "\"}")));
    assertThat(connections.access(U1)).isEqualTo("rotated-access");
    String encrypted =
        runtime.queryForObject("SELECT refresh_token FROM app.youtube_connection", String.class);
    assertThat(cipher.decrypt(U1, "refresh", encrypted)).isEqualTo("rotated-refresh");
    UUID completed = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    worker.tick();
    assertThat(job(completed).path("status").asText()).isEqualTo("SUCCEEDED");
    owner.execute("UPDATE app.youtube_connection SET expires_at=now()-interval '1 minute'");
    provider.stubFor(
        WireMock.post(urlEqualTo("/token"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"invalid_grant\"}")));
    assertThatThrownBy(() -> connections.access(U1)).hasMessage("YouTube 내보내기 상태를 확인해 주세요");
    assertThat(connections.status(U1).status()).isEqualTo("RECONNECT_REQUIRED");
    json(get(JOBS + completed), first, 404);
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.youtube_export_item", Long.class))
        .isZero();
    assertThat(
            runtime.queryForObject(
                "SELECT access_token IS NULL AND refresh_token IS NULL FROM app.youtube_connection",
                Boolean.class))
        .isTrue();
  }

  @Test
  void disconnectDeletesExportsAndRetriesRevocationWithoutLoggingOut() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    provider.stubFor(WireMock.post(urlEqualTo("/revoke")).willReturn(aResponse().withStatus(503)));
    assertThat(json(delete(CONNECTION), first, 200).path("status").asText()).isEqualTo("REVOKING");
    json(get(JOBS + id), first, 404);
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.youtube_export_item", Long.class))
        .isZero();
    assertThat(
            runtime.queryForObject(
                "SELECT access_token IS NULL AND expires_at IS NULL AND refresh_token IS NOT NULL FROM app.youtube_connection",
                Boolean.class))
        .isTrue();
    json(post(AUTH), first, 409);
    provider.stubFor(WireMock.post(urlEqualTo("/revoke")).willReturn(ok()));
    worker.tick();
    assertThat(json(get(CONNECTION), first, 200).path("status").asText()).isEqualTo("DISCONNECTED");
    assertThat(json(get("/api/v1/me"), first, 200).path("id").asText()).isEqualTo(U1.toString());
    provider.verify(0, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
  }

  @Test
  void disconnectPurgesCompletedCopiesAndAuthorizationButKeepsLocalPlaylistAndOtherUser()
      throws Exception {
    connected(U1);
    connected(U2);
    UUID playlist = playlist(S1);
    UUID completed = UUID.fromString(create(playlist, UUID.randomUUID()).path("id").asText());
    worker.tick();
    assertThat(job(completed).path("youtubePlaylistId").asText()).isEqualTo("PL_export");
    runtime.update(
        "INSERT INTO app.youtube_export(id,user_id,request_id,source_playlist_id,source_version,name,status) VALUES(?,?,?,?,0,'other','QUEUED')",
        UUID.randomUUID(),
        U2,
        UUID.randomUUID(),
        UUID.randomUUID());
    flow(first);
    json(delete(CONNECTION), first, 200);
    json(get(JOBS + completed), first, 404);
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM app.youtube_authorization WHERE user_id=?", Long.class, U1))
        .isZero();
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.youtube_export_item", Long.class))
        .isZero();
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM app.youtube_export WHERE user_id=?", Long.class, U2))
        .isEqualTo(1);
    assertThat(json(get("/api/v1/me/playlists/" + playlist), first, 200).path("totalCount").asInt())
        .isEqualTo(1);
    assertThat(connections.status(U2).status()).isEqualTo("CONNECTED");
    provider.verify(0, deleteRequestedFor(urlPathMatching("/youtube/v3/.*")));
  }

  @Test
  void lateWorkerResultsCannotRecreateDeletedExportAndOldRevocationCannotPurgeNewGrant()
      throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    var task = store.next().orElseThrow();
    var old = store.connection(U1).orElseThrow();
    json(delete(CONNECTION), first, 200);
    store.tx(
        () -> {
          store.created(task, "late-playlist");
          store.itemDone(task, 0, "COPIED");
          store.finish(task, "SUCCEEDED", null, true);
          return null;
        });
    json(get(JOBS + id), first, 404);
    owner.update("DELETE FROM app.youtube_connection WHERE user_id=?", U1);
    connected(U1);
    UUID current = UUID.fromString(create(playlist(S2), UUID.randomUUID()).path("id").asText());
    flow(first);
    store.tx(
        () -> {
          store.revoked(old);
          return null;
        });
    assertThat(job(current).path("status").asText()).isEqualTo("QUEUED");
    assertThat(connections.status(U1).status()).isEqualTo("CONNECTED");
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM app.youtube_authorization WHERE user_id=?", Long.class, U1))
        .isEqualTo(1);
  }

  @Test
  void accessCsrfVersionAndNoAvailableSongsAreCheckedBeforeExternalWrites() throws Exception {
    UUID p = playlist(S1);
    mvc.perform(get(CONNECTION)).andExpect(status().isUnauthorized());
    mvc.perform(post(AUTH).cookie(first.cookie())).andExpect(status().isForbidden());
    json(
        post("/api/v1/me/playlists/" + p + "/youtube-exports")
            .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"version\":0}"),
        second,
        404);
    json(
        post("/api/v1/me/playlists/" + p + "/youtube-exports")
            .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"version\":0}"),
        first,
        409);
    connected(U1);
    owner.execute("UPDATE app.song_entry SET visibility='HIDDEN'");
    json(
        post("/api/v1/me/playlists/" + p + "/youtube-exports")
            .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"version\":1}"),
        first,
        409);
    worker.tick();
    provider.verify(0, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
    for (String user : List.of("stelody_collector", "stelody_operator")) {
      var limited =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  postgres.getJdbcUrl(),
                  user,
                  user.equals("stelody_collector") ? "test-collector" : "test-operator"));
      assertThatThrownBy(() -> limited.queryForList("SELECT * FROM app.youtube_connection"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
  }

  @Test
  void concurrentRequestKeyCreatesOneSnapshotAndDbLockPreventsOverlappingWorker() throws Exception {
    connected(U1);
    UUID p = playlist(S1), request = UUID.randomUUID();
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<String>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return create(p, request).path("id").asText();
                }));
      assertThat(futures.get(0).get(10, TimeUnit.SECONDS))
          .isEqualTo(futures.get(1).get(10, TimeUnit.SECONDS));
    }
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.youtube_export", Long.class))
        .isEqualTo(1);
    try (var connection = owner.getDataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SELECT pg_advisory_lock(2026100301)");
      worker.tick();
      provider.verify(0, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
      statement.execute("SELECT pg_advisory_unlock(2026100301)");
    }
    worker.tick();
    assertThat(runtime.queryForObject("SELECT status FROM app.youtube_export", String.class))
        .isEqualTo("SUCCEEDED");
  }

  @Test
  void unavailableAfterRequestIsSkippedAndCancelNeverDeletesYouTubeCopy() throws Exception {
    connected(U1);
    UUID p = playlist(S1, S2);
    UUID id = UUID.fromString(create(p, UUID.randomUUID()).path("id").asText());
    owner.update("UPDATE app.song_entry SET visibility='HIDDEN' WHERE id=?", S2);
    worker.tick();
    assertThat(job(id).path("copiedCount").asInt()).isEqualTo(1);
    assertThat(job(id).path("skippedCount").asInt()).isEqualTo(1);
    json(post(JOBS + id + "/cancel"), first, 200);
    assertThat(job(id).path("status").asText()).isEqualTo("SUCCEEDED");
    UUID queued = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    json(post(JOBS + queued + "/cancel"), first, 200);
    worker.tick();
    assertThat(job(queued).path("status").asText()).isEqualTo("CANCELLED");
    provider.verify(1, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
    provider.verify(0, deleteRequestedFor(urlPathMatching("/youtube/v3/.*")));
  }

  @Test
  void expiredJobAndUnusedGrantAreCleanedAndRevoked() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    owner.execute("UPDATE app.youtube_export SET created_at=now()-interval '31 days'");
    owner.execute("UPDATE app.youtube_connection SET updated_at=now()-interval '31 days'");
    worker.tick();
    json(get(JOBS + id), first, 404);
    assertThat(connections.status(U1).status()).isEqualTo("DISCONNECTED");
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.youtube_export_item", Long.class))
        .isZero();
    provider.verify(1, postRequestedFor(urlEqualTo("/revoke")));
    provider.verify(0, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
  }

  @Test
  void suspendedAccountCannotExportAndItsGrantIsRevoked() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1), UUID.randomUUID()).path("id").asText());
    owner.update("UPDATE app.app_user SET status='SUSPENDED' WHERE id=?", U1);
    worker.tick();
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM app.youtube_export WHERE id=?", Long.class, id))
        .isZero();
    assertThat(connections.status(U1).status()).isEqualTo("DISCONNECTED");
    provider.verify(0, postRequestedFor(urlPathEqualTo("/youtube/v3/playlists")));
    json(get(CONNECTION), first, 401);
  }

  @Test
  void requestKeyReuseVersionChangeAndSingleActiveJobAreRejected() throws Exception {
    connected(U1);
    UUID p = playlist(S1), key = UUID.randomUUID();
    create(p, key);
    json(
        post("/api/v1/me/playlists/" + p + "/youtube-exports")
            .content("{\"requestId\":\"" + key + "\",\"version\":0}"),
        first,
        409);
    json(
        post("/api/v1/me/playlists/" + p + "/youtube-exports")
            .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"version\":1}"),
        first,
        409);
    json(
        post("/api/v1/me/playlists/" + p + "/youtube-exports")
            .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"),
        first,
        400);
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.youtube_export", Long.class))
        .isEqualTo(1);
  }

  @Test
  void fiveHundredItemSnapshotIsBoundedToTenWritesPerWorkerPass() throws Exception {
    connected(U1);
    UUID p = playlist(S1);
    owner.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) SELECT gen_random_uuid(),'batch','batch','COVER','PUBLISHED' FROM generate_series(1,499)");
    owner.execute(
        "INSERT INTO app.video(id,song_id,youtube_id,video_kind,availability,source_title,published_at) SELECT gen_random_uuid(),id,'B'||lpad(row_number() OVER (ORDER BY id)::text,10,'0'),'OFFICIAL_COVER','PUBLIC','test','2026-01-01' FROM app.song_entry WHERE title='batch'");
    owner.execute(
        "UPDATE app.song_entry s SET representative_video_id=v.id FROM app.video v WHERE v.song_id=s.id AND s.title='batch'");
    owner.execute(
        "INSERT INTO app.song_member(song_id,member_id,position) SELECT id,'00000000-0000-0000-0000-00000000000a',0 FROM app.song_entry WHERE title='batch'");
    owner.update(
        "INSERT INTO app.playlist_item(id,playlist_id,song_id,position,added_at) SELECT gen_random_uuid(),?,id,row_number() OVER (ORDER BY id),now() FROM app.song_entry WHERE title='batch'",
        p);
    UUID id = UUID.fromString(create(p, UUID.randomUUID()).path("id").asText());
    assertThat(job(id).path("totalCount").asInt()).isEqualTo(500);
    var batchIds = owner.queryForList("SELECT youtube_id FROM app.video", String.class);
    publicVideos(batchIds);
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("RUNNING");
    assertThat(job(id).path("copiedCount").asInt()).isEqualTo(10);
    provider.verify(10, postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
  }

  @Test
  void youtubePrivateOrMissingVideosAreExcludedEvenWhenCatalogStillSaysPublic() throws Exception {
    connected(U1);
    UUID id = UUID.fromString(create(playlist(S1, S2, S3), UUID.randomUUID()).path("id").asText());
    provider.stubFor(
        WireMock.get(urlPathEqualTo("/youtube/v3/videos"))
            .willReturn(
                okJson(
                    mapper.writeValueAsString(
                        Map.of(
                            "items",
                            List.of(
                                Map.of(
                                    "id",
                                    video(S1),
                                    "status",
                                    Map.of("privacyStatus", "public", "uploadStatus", "processed")),
                                Map.of(
                                    "id",
                                    video(S2),
                                    "status",
                                    Map.of(
                                        "privacyStatus",
                                        "private",
                                        "uploadStatus",
                                        "processed"))))))));
    worker.tick();
    assertThat(job(id).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(job(id).path("copiedCount").asInt()).isEqualTo(1);
    assertThat(job(id).path("skippedCount").asInt()).isEqualTo(2);
    provider.verify(1, postRequestedFor(urlPathEqualTo("/youtube/v3/playlistItems")));
  }
}
