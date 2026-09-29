package com.stelody;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stelody.auth.domain.SessionUser;
import com.stelody.user.service.AccountService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GoogleLoginIntegrationTest.ProviderConfiguration.class)
@Testcontainers
@Tag("integration")
class GoogleLoginIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  static final WireMockServer google = new WireMockServer(0);
  static final RSAKey key;

  static {
    try {
      key = new RSAKeyGenerator(2048).keyID("test-key").generate();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    google.start();
  }

  @AfterAll
  static void stopProvider() {
    google.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "stelody_app");
    registry.add("spring.datasource.password", () -> "test-runtime");
    registry.add("spring.flyway.user", () -> "stelody_migrator");
    registry.add("spring.flyway.password", () -> "test-migrator");
    registry.add("server.servlet.session.cookie.secure", () -> false);
  }

  @TestConfiguration
  static class ProviderConfiguration {
    @Bean
    ClientRegistrationRepository clients() {
      return new InMemoryClientRegistrationRepository(
          CommonOAuth2Provider.GOOGLE
              .getBuilder("google")
              .clientId("test-client")
              .clientSecret("test-secret")
              .scope("openid", "email")
              .issuerUri("https://accounts.google.com")
              .authorizationUri(google.baseUrl() + "/authorize")
              .tokenUri(google.baseUrl() + "/token")
              .jwkSetUri(google.baseUrl() + "/jwks")
              .userInfoUri(google.baseUrl() + "/userinfo")
              .redirectUri("{baseUrl}/api/v1/auth/callback/google")
              .build());
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccountService accounts;
  @Autowired FindByIndexNameSessionRepository<?> sessions;

  @BeforeEach
  void resetProvider() {
    google.resetAll();
    google.stubFor(
        get(urlEqualTo("/jwks")).willReturn(okJson(new JWKSet(key.toPublicJWK()).toString())));
  }

  class Browser {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    HttpResponse<String> get(String path) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> logout(String csrf) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/logout"))
              .POST(HttpRequest.BodyPublishers.noBody());
      if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    String cookie() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals("SESSION"))
          .findFirst()
          .orElseThrow()
          .getValue();
    }
  }

  record Authorization(String state, String nonce) {}

  Authorization authorize(Browser browser) throws Exception {
    var response = browser.get("/api/v1/auth/authorize/google");
    assertThat(response.statusCode()).isEqualTo(302);
    var query =
        org.springframework.web.util.UriComponentsBuilder.fromUriString(
                response.headers().firstValue("Location").orElseThrow())
            .build()
            .getQueryParams();
    String scope = URLDecoder.decode(query.getFirst("scope"), StandardCharsets.UTF_8);
    assertThat(scope).contains("openid", "email").doesNotContain("youtube", "offline");
    assertThat(query.getFirst("state")).isNotBlank();
    assertThat(query.getFirst("nonce")).isNotBlank();
    assertThat(query.getFirst("code_challenge")).isNotBlank();
    assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
    return new Authorization(query.getFirst("state"), query.getFirst("nonce"));
  }

  void tokens(Authorization authorization, String subject, String email, String defect)
      throws Exception {
    var now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(
                defect.equals("issuer") ? "https://invalid.example" : "https://accounts.google.com")
            .audience(defect.equals("audience") ? "other-client" : "test-client")
            .subject(subject)
            .issueTime(Date.from(now.minusSeconds(600)))
            .expirationTime(Date.from(now.plusSeconds(defect.equals("expired") ? -300 : 3600)))
            .claim("nonce", defect.equals("nonce") ? "wrong" : authorization.nonce())
            .claim("role", "ADMIN")
            .claim("email", email)
            .claim("email_verified", !defect.equals("unverified"))
            .build();
    var token =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
    token.sign(
        new RSASSASigner(defect.equals("signature") ? new RSAKeyGenerator(2048).generate() : key));
    google.stubFor(
        post(urlEqualTo("/token"))
            .willReturn(
                okJson(
                    mapper.writeValueAsString(
                        Map.of(
                            "access_token",
                            "test-google-access-token",
                            "token_type",
                            "Bearer",
                            "expires_in",
                            3600,
                            "scope",
                            "openid email",
                            "id_token",
                            token.serialize())))));
    google.stubFor(
        get(urlEqualTo("/userinfo"))
            .willReturn(
                okJson(
                    mapper.writeValueAsString(
                        Map.of(
                            "sub",
                            subject,
                            "email",
                            email,
                            "email_verified",
                            !defect.equals("unverified"))))));
  }

  HttpResponse<String> callback(Browser browser, Authorization auth) throws Exception {
    return browser.get("/api/v1/auth/callback/google?code=test-code&state=" + auth.state());
  }

  @Test
  void failedCallbacksPreserveExistingLogin() throws Exception {
    var browser = login(UUID.randomUUID().toString(), "preserved@example.invalid");
    google.resetRequests();
    var original = browser.get("/api/v1/me").body();
    var cookie = browser.cookie();
    assertThat(browser.get("/api/v1/auth/callback/google?error=access_denied").statusCode())
        .isEqualTo(401);
    assertThat(browser.get("/api/v1/me").body()).isEqualTo(original);
    var auth = authorize(browser);
    assertThat(browser.get("/api/v1/auth/callback/google?code=test-code&state=wrong").statusCode())
        .isEqualTo(401);
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
    assertThat(
            browser
                .get("/api/v1/auth/callback/google?error=access_denied&state=" + auth.state())
                .statusCode())
        .isEqualTo(401);
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
    assertThat(browser.get("/api/v1/me").body()).isEqualTo(original);
    assertThat(browser.cookie()).isEqualTo(cookie);
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
  }

  Browser login(String subject, String email) throws Exception {
    var browser = new Browser();
    var auth = authorize(browser);
    tokens(auth, subject, email, "none");
    var response = callback(browser, auth);
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/api/v1/me");
    return browser;
  }

  @Test
  void loginRotatesSessionUsesLocalIdentityAndLogoutRequiresFreshCsrf() throws Exception {
    var browser = new Browser();
    var oldCsrf = mapper.readTree(browser.get("/api/v1/auth/csrf").body()).get("token").asText();
    String oldCookie = browser.cookie();
    var auth = authorize(browser);
    tokens(auth, UUID.randomUUID().toString(), "login@example.invalid", "none");
    assertThat(callback(browser, auth).statusCode()).isEqualTo(302);
    assertThat(browser.cookie()).isNotEqualTo(oldCookie);
    var me = browser.get("/api/v1/me");
    assertThat(me.statusCode()).isEqualTo(200);
    var id = mapper.readTree(me.body()).get("id").asText();
    assertThat(mapper.readTree(me.body()).get("role").asText()).isEqualTo("USER");
    var stored = sessions.findByPrincipalName(id);
    assertThat(stored).hasSize(1);
    SecurityContext context =
        stored
            .values()
            .iterator()
            .next()
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(SessionUser.class);
    assertThat(context.getAuthentication().getCredentials()).isNull();
    assertThat(
            jdbc.query(
                "select attribute_bytes from session.spring_session_attributes",
                (rs, row) -> new String(rs.getBytes(1), StandardCharsets.ISO_8859_1)))
        .allSatisfy(value -> assertThat(value).doesNotContain("test-google-access-token"));
    assertThat(browser.logout(null).statusCode()).isEqualTo(403);
    assertThat(browser.logout(oldCsrf).statusCode()).isEqualTo(403);
    assertThat(browser.get("/api/v1/auth/logout").statusCode()).isEqualTo(403);
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
    String csrf = mapper.readTree(browser.get("/api/v1/auth/csrf").body()).get("token").asText();
    assertThat(browser.logout(csrf).statusCode()).isEqualTo(204);
    assertThat(sessions.findByPrincipalName(id)).isEmpty();
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
  }

  @Test
  void sameSubjectKeepsAccountEvenWhenEmailChangesAndEmailDoesNotMergeAccounts() throws Exception {
    String subject = UUID.randomUUID().toString();
    var first = login(subject, "before@example.invalid");
    String id = mapper.readTree(first.get("/api/v1/me").body()).get("id").asText();
    var again = login(subject, "after@example.invalid");
    assertThat(mapper.readTree(again.get("/api/v1/me").body()).get("id").asText()).isEqualTo(id);
    assertThat(mapper.readTree(again.get("/api/v1/me").body()).get("email").asText())
        .isEqualTo("after@example.invalid");
    var different = login(UUID.randomUUID().toString(), "after@example.invalid");
    assertThat(mapper.readTree(different.get("/api/v1/me").body()).get("id").asText())
        .isNotEqualTo(id);
  }

  @ParameterizedTest
  @ValueSource(strings = {"issuer", "audience", "nonce", "expired", "signature", "unverified"})
  void rejectsInvalidIdentityWithoutCreatingAccount(String defect) throws Exception {
    var browser = new Browser();
    var auth = authorize(browser);
    String subject = UUID.randomUUID().toString();
    tokens(auth, subject, "invalid@example.invalid", defect);
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from app.app_user where google_subject = ?",
                Integer.class,
                subject))
        .isZero();
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
  }

  @Test
  void rejectsStateMismatchAndCancelledConsent() throws Exception {
    var browser = new Browser();
    var auth = authorize(browser);
    assertThat(browser.get("/api/v1/auth/callback/google?code=test-code&state=wrong").statusCode())
        .isEqualTo(401);
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
    auth = authorize(browser);
    assertThat(
            browser
                .get("/api/v1/auth/callback/google?error=access_denied&state=" + auth.state())
                .statusCode())
        .isEqualTo(401);
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
  }

  @Test
  void rejectsCallbackReplayAndExternalReturnParameter() throws Exception {
    var browser = new Browser();
    var auth = authorize(browser);
    tokens(auth, UUID.randomUUID().toString(), "replay@example.invalid", "none");
    var result =
        browser.get(
            "/api/v1/auth/callback/google?code=test-code&state="
                + auth.state()
                + "&returnTo=https://evil.example");
    assertThat(result.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/api/v1/me");
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
  }

  @Test
  void suspendedAccountLosesAllSessionsAndCannotSignInAgain() throws Exception {
    String subject = UUID.randomUUID().toString();
    var first = login(subject, "suspended@example.invalid");
    var second = login(subject, "suspended@example.invalid");
    String id = mapper.readTree(first.get("/api/v1/me").body()).get("id").asText();
    jdbc.update("update app.app_user set status = 'SUSPENDED' where id = ?", UUID.fromString(id));
    assertThat(first.get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(sessions.findByPrincipalName(id)).isEmpty();
    assertThat(second.get("/api/v1/me").statusCode()).isEqualTo(401);
    var browser = new Browser();
    var auth = authorize(browser);
    tokens(auth, subject, "suspended@example.invalid", "none");
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
  }

  @Test
  void absoluteAndIdleSessionExpiryRequireLogin() throws Exception {
    for (boolean absolute : new boolean[] {true, false}) {
      var browser = login(UUID.randomUUID().toString(), "expiry@example.invalid");
      String id = mapper.readTree(browser.get("/api/v1/me").body()).get("id").asText();
      expireSession(sessions, id, absolute);
      assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
    }
  }

  private <S extends Session> void expireSession(
      FindByIndexNameSessionRepository<S> repository, String id, boolean absolute) {
    var session = repository.findByPrincipalName(id).values().iterator().next();
    if (absolute) {
      SecurityContext context =
          session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              new SessionUser(UUID.fromString(id), Instant.now().minusSeconds(43201)),
              null,
              context.getAuthentication().getAuthorities()));
      session.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    } else session.setLastAccessedTime(Instant.now().minusSeconds(1801));
    repository.save(session);
  }

  @Test
  void concurrentFirstLoginsCreateOneAccount() {
    String subject = UUID.randomUUID().toString();
    var a =
        CompletableFuture.supplyAsync(() -> accounts.signIn(subject, "parallel@example.invalid"));
    var b =
        CompletableFuture.supplyAsync(() -> accounts.signIn(subject, "parallel@example.invalid"));
    assertThat(a.join().id()).isEqualTo(b.join().id());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from app.app_user where google_subject = ?",
                Integer.class,
                subject))
        .isEqualTo(1);
  }
}
