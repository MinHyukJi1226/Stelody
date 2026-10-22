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
import com.stelody.auth.domain.LoginReturn;
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
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
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
    registry.add(
        "stelody.auth.google.local-redirect-uri",
        () -> "http://localhost:5173/api/v1/auth/callback/google");
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

    void allowSecureLocalhostCookiesForJdkTestClient() {
      // Real browsers allow Secure cookies on localhost. The JDK HTTP test client does not;
      // emulate that localhost exception only in its test cookie jar, never on the server.
      cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals("STELODY_LOGIN"))
          .forEach(c -> c.setSecure(false));
    }

    HttpResponse<String> get(String path) throws Exception {
      allowSecureLocalhostCookiesForJdkTestClient();
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> logout(String csrf) throws Exception {
      allowSecureLocalhostCookiesForJdkTestClient();
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

    String csrf() throws Exception {
      return mapper.readTree(get("/api/v1/auth/csrf").body()).get("token").asText();
    }

    HttpResponse<String> mutate(String method, String path, String csrf) throws Exception {
      allowSecureLocalhostCookiesForJdkTestClient();
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .method(method, HttpRequest.BodyPublishers.noBody());
      if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  JdbcTemplate owner() {
    return new JdbcTemplate(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
  }

  UUID accountId(Browser browser) throws Exception {
    return UUID.fromString(mapper.readTree(browser.get("/api/v1/me").body()).get("id").asText());
  }

  Authorization reauthenticate(Browser browser) throws Exception {
    var result = browser.mutate("POST", "/api/v1/me/reauthentications", browser.csrf());
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(result.headers().firstValue("Cache-Control")).hasValue("no-store");
    var query =
        org.springframework.web.util.UriComponentsBuilder.fromUriString(
                mapper.readTree(result.body()).get("authorizationUrl").asText())
            .build()
            .getQueryParams();
    assertThat(query.getFirst("prompt")).isEqualTo("select_account");
    assertThat(URLDecoder.decode(query.getFirst("scope"), StandardCharsets.UTF_8))
        .contains("openid", "email")
        .doesNotContain("youtube", "offline");
    assertThat(URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8))
        .isEqualTo("http://localhost:" + port + "/api/v1/auth/callback/google");
    assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
    assertThat(query.getFirst("code_challenge")).isNotBlank();
    return new Authorization(query.getFirst("state"), query.getFirst("nonce"));
  }

  SessionUser sessionUser(UUID id) {
    SecurityContext context =
        sessions
            .findByPrincipalName(id.toString())
            .values()
            .iterator()
            .next()
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    return (SessionUser) context.getAuthentication().getPrincipal();
  }

  @Test
  void recentLoginAloneCannotWithdrawAndReauthenticationRequiresCsrf() throws Exception {
    var anonymous = new Browser();
    assertThat(anonymous.get("/api/v1/me/withdrawal-confirmation").statusCode()).isEqualTo(401);
    assertThat(
            anonymous.mutate("POST", "/api/v1/me/reauthentications", anonymous.csrf()).statusCode())
        .isEqualTo(401);
    var browser = login(UUID.randomUUID().toString(), "reauth@example.invalid");
    assertThat(browser.mutate("POST", "/api/v1/me/reauthentications", null).statusCode())
        .isEqualTo(403);
    assertThat(browser.mutate("DELETE", "/api/v1/me", null).statusCode()).isEqualTo(403);
    var delete = browser.mutate("DELETE", "/api/v1/me", browser.csrf());
    assertThat(delete.statusCode()).isEqualTo(409);
    assertThat(delete.body()).contains("REAUTHENTICATION_REQUIRED");
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
  }

  @Test
  void sameGoogleAccountConfirmsForFiveMinutesWithoutExtendingLoginAndWithdrawsAllSessions()
      throws Exception {
    String subject = UUID.randomUUID().toString();
    var browser = login(subject, "withdraw@example.invalid");
    UUID id = accountId(browser);
    SessionUser previous = sessionUser(id);
    var other = login(subject, "withdraw@example.invalid");
    String oldCookie = browser.cookie(), oldCsrf = browser.csrf();
    var authorization = reauthenticate(browser);
    tokens(authorization, subject, "changed@example.invalid", "none");
    var result = callback(browser, authorization);
    assertThat(result.statusCode()).isEqualTo(302);
    assertThat(result.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/api/v1/me/withdrawal-confirmation");
    assertThat(browser.cookie()).isNotEqualTo(oldCookie);
    assertThat(sessions.findByPrincipalName(id.toString()).values())
        .allSatisfy(
            session -> {
              SecurityContext context =
                  session.getAttribute(
                      HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
              assertThat(context.getAuthentication().getPrincipal())
                  .isInstanceOf(SessionUser.class);
            });
    assertThat(
            sessions.findByPrincipalName(id.toString()).values().stream()
                .anyMatch(
                    s -> {
                      SecurityContext context =
                          s.getAttribute(
                              HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                      return previous.equals(context.getAuthentication().getPrincipal());
                    }))
        .isTrue();
    assertThat(browser.get("/api/v1/me").body()).contains("withdraw@example.invalid");
    var proof = mapper.readTree(browser.get("/api/v1/me/withdrawal-confirmation").body());
    assertThat(proof.get("reauthenticated").asBoolean()).isTrue();
    assertThat(Instant.parse(proof.get("expiresAt").asText()))
        .isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(301));
    assertThat(
            mapper
                .readTree(other.get("/api/v1/me/withdrawal-confirmation").body())
                .get("reauthenticated")
                .asBoolean())
        .isFalse();
    assertThat(other.mutate("DELETE", "/api/v1/me", other.csrf()).statusCode()).isEqualTo(409);
    assertThat(browser.mutate("DELETE", "/api/v1/me", oldCsrf).statusCode()).isEqualTo(403);
    var withdrawn = browser.mutate("DELETE", "/api/v1/me", browser.csrf());
    assertThat(withdrawn.statusCode()).isEqualTo(204);
    assertThat(withdrawn.headers().allValues("Set-Cookie"))
        .anySatisfy(c -> assertThat(c).contains("SESSION=", "Max-Age=0"));
    assertThat(sessions.findByPrincipalName(id.toString())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.persistent_login WHERE user_id=?", Integer.class, id))
        .isZero();
    assertThat(withdrawn.headers().allValues("Set-Cookie"))
        .anySatisfy(
            c ->
                assertThat(c)
                    .contains("STELODY_LOGIN=", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Lax"));
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(other.get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject("select count(*) from app.app_user where id=?", Integer.class, id))
        .isZero();
    assertThat(accountId(login(subject, "withdraw@example.invalid"))).isNotEqualTo(id);
  }

  @Test
  void differentGoogleSubjectWithSameEmailDoesNotSwitchOrCreateAccount() throws Exception {
    var browser = login(UUID.randomUUID().toString(), "same@example.invalid");
    var original = browser.get("/api/v1/me").body();
    var id = accountId(browser);
    var previous = sessionUser(id);
    var authorization = reauthenticate(browser);
    String different = UUID.randomUUID().toString(), cookie = browser.cookie();
    tokens(authorization, different, "same@example.invalid", "none");
    assertThat(callback(browser, authorization).statusCode()).isEqualTo(401);
    assertThat(browser.cookie()).isEqualTo(cookie);
    assertThat(browser.get("/api/v1/me").body()).isEqualTo(original);
    assertThat(sessionUser(id)).isEqualTo(previous);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from app.app_user where google_subject=?",
                Integer.class,
                different))
        .isZero();
    assertThat(browser.mutate("DELETE", "/api/v1/me", browser.csrf()).statusCode()).isEqualTo(409);
  }

  @ParameterizedTest
  @ValueSource(strings = {"issuer", "audience", "nonce", "expired", "signature", "unverified"})
  void invalidReauthenticationNeverGrantsWithdrawal(String defect) throws Exception {
    String subject = UUID.randomUUID().toString();
    var browser = login(subject, "invalid-reauth@example.invalid");
    String original = browser.get("/api/v1/me").body();
    var auth = reauthenticate(browser);
    tokens(auth, subject, "invalid-reauth@example.invalid", defect);
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
    assertThat(browser.get("/api/v1/me").body()).isEqualTo(original);
    assertThat(browser.mutate("DELETE", "/api/v1/me", browser.csrf()).statusCode()).isEqualTo(409);
  }

  @Test
  void expiredReauthenticationCannotExchangeTokensAndReplayCannotGrantAgain() throws Exception {
    String subject = UUID.randomUUID().toString();
    var browser = login(subject, "expired-reauth@example.invalid");
    UUID id = accountId(browser);
    var auth = reauthenticate(browser);
    owner()
        .update(
            "update app.account_reauthentication set expires_at=clock_timestamp()-interval '1 second' where user_id=?",
            id);
    google.resetRequests();
    tokens(auth, subject, "expired-reauth@example.invalid", "none");
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
    auth = reauthenticate(browser);
    tokens(auth, subject, "expired-reauth@example.invalid", "none");
    assertThat(callback(browser, auth).statusCode()).isEqualTo(302);
    google.resetRequests();
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
    owner()
        .update(
            "update app.account_reauthentication set expires_at=clock_timestamp()-interval '1 second' where user_id=?",
            id);
    assertThat(
            mapper
                .readTree(browser.get("/api/v1/me/withdrawal-confirmation").body())
                .get("reauthenticated")
                .asBoolean())
        .isFalse();
    assertThat(browser.mutate("DELETE", "/api/v1/me", browser.csrf()).statusCode()).isEqualTo(409);
  }

  @Test
  void cancelledWrongStateOtherBrowserAndSupersededRequestsCannotConfirm() throws Exception {
    String subject = UUID.randomUUID().toString();
    var browser = login(subject, "state-reauth@example.invalid");
    var other = login(subject, "state-reauth@example.invalid");
    var old = reauthenticate(browser);
    var current = reauthenticate(browser);
    google.resetRequests();
    assertThat(callback(browser, old).statusCode()).isEqualTo(401);
    assertThat(callback(other, current).statusCode()).isEqualTo(401);
    assertThat(browser.get("/api/v1/auth/callback/google?code=test-code&state=wrong").statusCode())
        .isEqualTo(401);
    assertThat(
            browser
                .get("/api/v1/auth/callback/google?error=access_denied&state=" + current.state())
                .statusCode())
        .isEqualTo(401);
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
    assertThat(browser.mutate("DELETE", "/api/v1/me", browser.csrf()).statusCode()).isEqualTo(409);
  }

  @Test
  void concurrentCallbacksExchangeTokensOnlyOnce() throws Exception {
    String subject = UUID.randomUUID().toString();
    var browser = login(subject, "parallel-reauth@example.invalid");
    var auth = reauthenticate(browser);
    tokens(auth, subject, "parallel-reauth@example.invalid", "none");
    google.resetRequests();
    var first = CompletableFuture.supplyAsync(() -> callbackStatus(browser, auth));
    var second = CompletableFuture.supplyAsync(() -> callbackStatus(browser, auth));
    assertThat(java.util.List.of(first.join(), second.join())).containsExactlyInAnyOrder(302, 401);
    google.verify(1, postRequestedFor(urlEqualTo("/token")));
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
  }

  int callbackStatus(Browser browser, Authorization authorization) {
    try {
      return callback(browser, authorization).statusCode();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void confirmationStorageFailureRetainsOnlyLocalIdentityAndCannotGrantWithdrawal()
      throws Exception {
    String subject = UUID.randomUUID().toString();
    var browser = login(subject, "storage-reauth@example.invalid");
    UUID id = accountId(browser);
    SessionUser previous = sessionUser(id);
    var auth = reauthenticate(browser);
    tokens(auth, subject, "storage-reauth@example.invalid", "none");
    // Permit consuming PENDING, but fail only the final confirmation write.
    owner()
        .execute(
            """
        CREATE FUNCTION app.fail_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN IF NEW.status='CONFIRMED' THEN RAISE EXCEPTION 'private-storage-detail'; END IF;
        RETURN NEW; END $$
        """);
    owner()
        .execute(
            "CREATE TRIGGER fail_confirmation BEFORE UPDATE ON app.account_reauthentication FOR EACH ROW EXECUTE FUNCTION app.fail_confirmation()");
    try {
      var result = callback(browser, auth);
      assertThat(result.statusCode()).isEqualTo(503);
      assertThat(result.body())
          .contains("ACCOUNT_STORAGE_UNAVAILABLE")
          .doesNotContain("private-storage-detail");
      assertThat(sessionUser(id)).isEqualTo(previous);
      assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
      assertThat(browser.mutate("DELETE", "/api/v1/me", browser.csrf()).statusCode())
          .isEqualTo(409);
      assertThat(
              jdbc.query(
                  "select attribute_bytes from session.spring_session_attributes",
                  (r, n) -> new String(r.getBytes(1), StandardCharsets.ISO_8859_1)))
          .allSatisfy(
              value ->
                  assertThat(value).doesNotContain("test-google-access-token", "LocalOidcUser"));
    } finally {
      owner().execute("DROP TRIGGER fail_confirmation ON app.account_reauthentication");
      owner().execute("DROP FUNCTION app.fail_confirmation()");
    }
  }

  record Authorization(String state, String nonce) {}

  Authorization authorize(Browser browser) throws Exception {
    return authorize(browser, "/api/v1/auth/authorize/google");
  }

  Authorization authorize(Browser browser, String path) throws Exception {
    var response = browser.get(path);
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
    assertThat(query).doesNotContainKeys("returnTo", "loginResult");
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

  String returnQuery(String path) {
    return "?returnTo=" + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8);
  }

  @Test
  void localProxyFlowUsesConfiguredOriginForStartTokenExchangeAndSuccess() throws Exception {
    var browser = new Browser();
    browser.csrf();
    String oldCookie = browser.cookie();
    String target = "/login?returnTo=%2Flibrary";
    var start = browser.get("/api/v1/auth/google" + returnQuery(target) + "&loginClient=local");
    assertThat(start.statusCode()).isEqualTo(302);
    URI next = URI.create(start.headers().firstValue("Location").orElseThrow());
    assertThat(next.toString()).startsWith("http://localhost:5173/api/v1/auth/authorize/google?");
    assertThat(next.getRawQuery()).contains("loginClient=local");
    // The local proxy forwards this URL to the backend while changing its Host.
    var auth = authorize(browser, next.getRawPath() + "?" + next.getRawQuery());
    tokens(auth, UUID.randomUUID().toString(), "local-login@example.invalid", "none");
    var result =
        browser.get(
            "/api/v1/auth/callback/google?code=test-code&state="
                + auth.state()
                + "&loginClient=https://evil.example&returnTo=https://evil.example");
    assertThat(result.statusCode()).isEqualTo(302);
    assertThat(result.headers().firstValue("Location"))
        .hasValue("http://localhost:5173/login?returnTo=%2Flibrary&loginResult=success");
    google.verify(
        postRequestedFor(urlEqualTo("/token"))
            .withFormParam(
                "redirect_uri", equalTo("http://localhost:5173/api/v1/auth/callback/google"))
            .withFormParam("code_verifier", matching(".+")));
    assertThat(browser.cookie()).isNotEqualTo(oldCookie);
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
    // Selection is per OAuth request, not per session: a later standard login stays standard.
    var standard = authorize(browser);
    tokens(standard, UUID.randomUUID().toString(), "standard-after-local@example.invalid", "none");
    assertThat(callback(browser, standard).headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/api/v1/me");
  }

  @Test
  void localAuthorizationHasConfiguredCallbackAndPkceWithoutLeakingSelectorToProvider()
      throws Exception {
    var result = new Browser().get("/api/v1/auth/authorize/google?loginClient=local");
    var query =
        org.springframework.web.util.UriComponentsBuilder.fromUriString(
                result.headers().firstValue("Location").orElseThrow())
            .build()
            .getQueryParams();
    assertThat(URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8))
        .isEqualTo("http://localhost:5173/api/v1/auth/callback/google");
    assertThat(query).doesNotContainKeys("loginClient", "returnTo");
    assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
  }

  @Test
  void forwardedHostHeadersCannotSelectTheCallbackOrStartDestination() throws Exception {
    var browser = new Browser();
    for (boolean local : new boolean[] {true, false}) {
      String path =
          local
              ? "/api/v1/auth/google?loginClient=local&returnTo=%2Flogin"
              : "/api/v1/auth/authorize/google";
      var result =
          browser.client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                  .header("Forwarded", "host=evil.example;proto=https")
                  .header("X-Forwarded-Host", "evil.example")
                  .header("X-Forwarded-Proto", "https")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      String location = result.headers().firstValue("Location").orElseThrow();
      assertThat(location).doesNotContain("evil.example");
      if (local)
        assertThat(location).startsWith("http://localhost:5173/api/v1/auth/authorize/google?");
      else {
        var params =
            org.springframework.web.util.UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams();
        assertThat(URLDecoder.decode(params.getFirst("redirect_uri"), StandardCharsets.UTF_8))
            .isEqualTo("http://localhost:" + port + "/api/v1/auth/callback/google");
      }
    }
  }

  @Test
  void localCancellationFailureAndDefaultSuccessUseOnlyStoredClient() throws Exception {
    for (String outcome : java.util.List.of("cancelled", "failed", "success")) {
      var browser = new Browser();
      var auth =
          authorize(
              browser,
              "/api/v1/auth/authorize/google?loginClient=local"
                  + (outcome.equals("success") ? "" : "&returnTo=%2Flogin"));
      HttpResponse<String> result;
      if (outcome.equals("cancelled")) {
        result =
            browser.get("/api/v1/auth/callback/google?error=access_denied&state=" + auth.state());
      } else {
        tokens(
            auth,
            UUID.randomUUID().toString(),
            "local-outcome@example.invalid",
            outcome.equals("failed") ? "nonce" : "none");
        result = callback(browser, auth);
      }
      assertThat(result.statusCode()).isEqualTo(302);
      assertThat(result.headers().firstValue("Location"))
          .hasValue(
              "http://localhost:5173"
                  + (outcome.equals("success") ? "/api/v1/me" : "/login?loginResult=" + outcome));
      assertThat(browser.get("/api/v1/me").statusCode())
          .isEqualTo(outcome.equals("success") ? 200 : 401);
    }
  }

  @Test
  void invalidClientAndWrongStateCannotChooseALocalRedirect() throws Exception {
    for (String path : java.util.List.of("/api/v1/auth/google", "/api/v1/auth/authorize/google")) {
      for (String query :
          java.util.List.of(
              "loginClient=",
              "loginClient=other",
              "loginClient=https://evil.example",
              "loginClient=local&loginClient=local",
              "loginClient=local&returnTo=https://evil.example")) {
        var browser = new Browser();
        var result = browser.get(path + "?" + query);
        assertThat(result.statusCode()).isEqualTo(400);
        assertThat(result.headers().firstValue("Location")).isEmpty();
        assertThat(browser.cookies.getCookieStore().getCookies()).isEmpty();
      }
    }
    var browser = new Browser();
    var auth =
        authorize(browser, "/api/v1/auth/authorize/google?loginClient=local&returnTo=%2Flogin");
    var wrong =
        browser.get("/api/v1/auth/callback/google?code=test-code&state=wrong&loginClient=local");
    assertThat(wrong.statusCode()).isEqualTo(401);
    assertThat(wrong.headers().firstValue("Location")).isEmpty();
    tokens(auth, UUID.randomUUID().toString(), "after-wrong-state@example.invalid", "none");
    assertThat(callback(browser, auth).statusCode()).isEqualTo(302);
  }

  @Test
  void publicStartForwardsEncodedReturnPathAndSuccessPreservesFiltersAndRotatesSession()
      throws Exception {
    var browser = new Browser();
    browser.csrf();
    String cookie = browser.cookie();
    String target = "/songs?q=%ED%95%9C%EA%B8%80&member=a&member=b#results";
    var start = browser.get("/api/v1/auth/google" + returnQuery(target));
    assertThat(start.statusCode()).isEqualTo(302);
    assertThat(start.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(start.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
    String forwarded =
        URI.create(start.headers().firstValue("Location").orElseThrow()).getRawQuery();
    assertThat(URLDecoder.decode(forwarded.substring("returnTo=".length()), StandardCharsets.UTF_8))
        .isEqualTo(target);
    var auth = authorize(browser, "/api/v1/auth/authorize/google?" + forwarded);
    tokens(auth, UUID.randomUUID().toString(), "return@example.invalid", "none");
    var result =
        browser.get(
            "/api/v1/auth/callback/google?code=test-code&state="
                + auth.state()
                + "&returnTo=https://evil.example");
    assertThat(result.statusCode()).isEqualTo(302);
    assertThat(result.headers().firstValue("Location"))
        .hasValue(
            "http://localhost:"
                + port
                + "/songs?q=%ED%95%9C%EA%B8%80&member=a&member=b&loginResult=success#results");
    assertThat(result.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
    assertThat(browser.cookie()).isNotEqualTo(cookie);
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://evil.example",
        "//evil.example",
        "/\\evil.example",
        "/%2f%2fevil.example",
        "/%252fevil.example",
        "/songs/../api/v1/auth/google",
        "/api/v1/auth/google",
        "/actuator/env",
        ""
      })
  void bothLoginEntryPointsRejectUnsafeReturnWithoutCreatingSession(String target)
      throws Exception {
    for (String path : java.util.List.of("/api/v1/auth/google", "/api/v1/auth/authorize/google")) {
      var browser = new Browser();
      var result = browser.get(path + returnQuery(target));
      assertThat(result.statusCode()).isEqualTo(400);
      var problem = mapper.readTree(result.body());
      assertThat(problem.get("code").asText()).isEqualTo("INVALID_LOGIN_RETURN");
      assertThat(problem.get("title").asText()).isEqualTo("로그인 복귀 경로가 올바르지 않습니다");
      assertThat(result.headers().firstValue("Location")).isEmpty();
      assertThat(browser.cookies.getCookieStore().getCookies()).isEmpty();
    }
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void duplicateReturnPathsAreRejected() throws Exception {
    var result =
        new Browser().get("/api/v1/auth/authorize/google?returnTo=%2Fsongs&returnTo=%2Fmembers");
    assertThat(result.statusCode()).isEqualTo(400);
    assertThat(result.body()).contains("INVALID_LOGIN_RETURN");
  }

  @Test
  void cancellationReturnsToSameScreenWithExplicitResultAndNoLogin() throws Exception {
    var browser = new Browser();
    var auth =
        authorize(
            browser,
            "/api/v1/auth/authorize/google"
                + returnQuery("/songs?q=cover&loginResult=success#results"));
    var result =
        browser.get("/api/v1/auth/callback/google?error=access_denied&state=" + auth.state());
    assertThat(result.statusCode()).isEqualTo(302);
    assertThat(result.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/songs?q=cover&loginResult=cancelled#results");
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void invalidIdentityReturnsFailureWithoutCreatingAccount() throws Exception {
    var browser = new Browser();
    var auth = authorize(browser, "/api/v1/auth/authorize/google" + returnQuery("/songs?q=cover"));
    String subject = UUID.randomUUID().toString();
    tokens(auth, subject, "failed-return@example.invalid", "nonce");
    var result = callback(browser, auth);
    assertThat(result.statusCode()).isEqualTo(302);
    assertThat(result.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/songs?q=cover&loginResult=failed");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.app_user WHERE google_subject=?", Integer.class, subject))
        .isZero();
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
  }

  @Test
  void unknownStateCannotRedirectOrConsumeNewestReturnAndCallbackCannotOverrideIt()
      throws Exception {
    var browser = new Browser();
    var old = authorize(browser, "/api/v1/auth/authorize/google" + returnQuery("/songs"));
    var current =
        authorize(browser, "/api/v1/auth/authorize/google" + returnQuery("/members?q=rin"));
    for (Authorization wrong : java.util.List.of(old, new Authorization("unknown", "unused"))) {
      var result =
          browser.get(
              "/api/v1/auth/callback/google?error=access_denied&state="
                  + wrong.state()
                  + "&returnTo=%2Fsongs");
      assertThat(result.statusCode()).isEqualTo(401);
      assertThat(result.headers().firstValue("Location")).isEmpty();
    }
    var otherBrowser = new Browser();
    assertThat(callback(otherBrowser, current).statusCode()).isEqualTo(401);
    var result =
        browser.get(
            "/api/v1/auth/callback/google?error=access_denied&state="
                + current.state()
                + "&returnTo=https://evil.example");
    assertThat(result.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/members?q=rin&loginResult=cancelled");
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void cancelledReturnFlowPreservesExistingAccountAndSession() throws Exception {
    var browser = login(UUID.randomUUID().toString(), "keep-return@example.invalid");
    String original = browser.get("/api/v1/me").body(), cookie = browser.cookie();
    var auth = authorize(browser, "/api/v1/auth/authorize/google" + returnQuery("/playlists"));
    var result =
        browser.get("/api/v1/auth/callback/google?error=access_denied&state=" + auth.state());
    assertThat(result.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/playlists?loginResult=cancelled");
    assertThat(browser.get("/api/v1/me").body()).isEqualTo(original);
    assertThat(browser.cookie()).isEqualTo(cookie);
  }

  @Test
  void expiredReturnStoredInJdbcSessionCannotExchangeTokensOrRedirect() throws Exception {
    var browser = login(UUID.randomUUID().toString(), "return-expiry@example.invalid");
    String original = browser.get("/api/v1/me").body();
    String id = accountId(browser).toString();
    var auth = authorize(browser, "/api/v1/auth/authorize/google" + returnQuery("/songs"));
    expireLoginReturn(sessions, id);
    google.resetRequests();
    var result = callback(browser, auth);
    assertThat(result.statusCode()).isEqualTo(401);
    assertThat(result.headers().firstValue("Location")).isEmpty();
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
    assertThat(browser.get("/api/v1/me").body()).isEqualTo(original);
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
    var fresh = authorize(browser);
    result = browser.get("/api/v1/auth/callback/google?error=access_denied&state=" + fresh.state());
    assertThat(result.statusCode()).isEqualTo(401);
    assertThat(result.headers().firstValue("Location")).isEmpty();
  }

  private <S extends Session> void expireLoginReturn(
      FindByIndexNameSessionRepository<S> repository, String id) {
    var session = repository.findByPrincipalName(id).values().iterator().next();
    String attribute =
        session.getAttributeNames().stream()
            .filter(name -> session.getAttribute(name) instanceof OAuth2AuthorizationRequest)
            .findFirst()
            .orElseThrow();
    OAuth2AuthorizationRequest authorization = session.getAttribute(attribute);
    LoginReturn target = authorization.getAttribute(LoginReturn.ATTRIBUTE);
    session.setAttribute(
        attribute,
        OAuth2AuthorizationRequest.from(authorization)
            .attributes(
                a ->
                    a.put(
                        LoginReturn.ATTRIBUTE,
                        new LoginReturn(target.path(), Instant.now().minusSeconds(1))))
            .build());
    repository.save(session);
  }

  @Test
  void reauthenticationIgnoresPreviousReturnAndCallbackReturnWithoutExtendingLogin()
      throws Exception {
    String subject = UUID.randomUUID().toString();
    var browser = login(subject, "return-reauth@example.invalid");
    UUID id = accountId(browser);
    SessionUser previous = sessionUser(id);
    var old = authorize(browser, "/api/v1/auth/authorize/google" + returnQuery("/songs"));
    var auth = reauthenticate(browser);
    tokens(auth, subject, "return-reauth@example.invalid", "none");
    var result =
        browser.get(
            "/api/v1/auth/callback/google?code=test-code&state="
                + auth.state()
                + "&returnTo=%2Fsongs");
    assertThat(result.statusCode()).isEqualTo(302);
    assertThat(result.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/api/v1/me/withdrawal-confirmation");
    assertThat(sessionUser(id)).isEqualTo(previous);
    assertThat(
            mapper
                .readTree(browser.get("/api/v1/me/withdrawal-confirmation").body())
                .get("reauthenticated")
                .asBoolean())
        .isTrue();
    result = callback(browser, old);
    assertThat(result.statusCode()).isEqualTo(401);
    assertThat(result.headers().firstValue("Location")).isEmpty();
    google.verify(2, postRequestedFor(urlEqualTo("/token")));
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
    // Suspension is an operator action; the web role intentionally cannot change account status.
    var operator =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    operator.update(
        "update app.app_user set status = 'SUSPENDED' where id = ?", UUID.fromString(id));
    assertThat(first.get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(sessions.findByPrincipalName(id)).isEmpty();
    assertThat(second.get("/api/v1/me").statusCode()).isEqualTo(401);
    var browser = new Browser();
    var auth = authorize(browser);
    tokens(auth, subject, "suspended@example.invalid", "none");
    assertThat(callback(browser, auth).statusCode()).isEqualTo(401);
  }

  @Test
  void absoluteAndIdleSessionExpiryRestoreLoginWithoutGoogleInteraction() throws Exception {
    for (boolean absolute : new boolean[] {true, false}) {
      var browser = login(UUID.randomUUID().toString(), "expiry@example.invalid");
      String id = mapper.readTree(browser.get("/api/v1/me").body()).get("id").asText();
      expireSession(sessions, id, absolute);
      google.resetRequests();
      assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
      google.verify(0, postRequestedFor(urlEqualTo("/token")));
      browser.cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals("STELODY_LOGIN"))
          .toList()
          .forEach(c -> browser.cookies.getCookieStore().remove(null, c));
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
              new SessionUser(
                  UUID.fromString(id),
                  Instant.now().minusSeconds(43201),
                  ((SessionUser) context.getAuthentication().getPrincipal()).loginId(),
                  ((SessionUser) context.getAuthentication().getPrincipal()).loginExpiresAt()),
              null,
              context.getAuthentication().getAuthorities()));
      session.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    } else session.setLastAccessedTime(Instant.now().minusSeconds(1801));
    repository.save(session);
  }

  String loginCookie(Browser browser) {
    return browser.cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("STELODY_LOGIN") && !c.getValue().isEmpty())
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  Browser rememberedBrowser(String cookie) {
    var browser = new Browser();
    var c = new java.net.HttpCookie("STELODY_LOGIN", cookie);
    c.setPath("/");
    c.setDomain("localhost.local");
    c.setVersion(0);
    browser.cookies.getCookieStore().add(URI.create("http://localhost:" + port), c);
    return browser;
  }

  @Test
  void regularGoogleLoginCreatesOnlyHashedDeviceCredentialAndPersistentSecureCookie()
      throws Exception {
    var browser = new Browser();
    var authorization = authorize(browser);
    tokens(authorization, UUID.randomUUID().toString(), "remember@example.invalid", "none");
    var result = callback(browser, authorization);
    assertThat(result.statusCode()).isEqualTo(302);
    String header =
        result.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("STELODY_LOGIN=") && !c.contains("Max-Age=0"))
            .findFirst()
            .orElseThrow();
    assertThat(header).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/");
    long age = Long.parseLong(header.split("Max-Age=")[1].split(";")[0]);
    assertThat(age).isBetween(2591990L, 2592000L);
    String cookie = loginCookie(browser);
    String selector = cookie.substring(0, 36), secret = cookie.substring(37);
    var row =
        jdbc.queryForMap(
            "SELECT * FROM app.persistent_login WHERE id=?", UUID.fromString(selector));
    assertThat(row.get("token_hash").toString()).hasSize(64).doesNotContain(secret);
    assertThat(row.toString()).doesNotContain(secret, "test-access", "test-refresh");
    assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant())
        .isEqualTo(((java.sql.Timestamp) row.get("created_at")).toInstant().plusSeconds(2592000));
    assertThat(sessionUser(accountId(browser)).loginId()).isEqualTo(UUID.fromString(selector));
  }

  @Test
  void browserRestartRestoresIdentityRotatesCredentialAndKeepsTheOriginalThirtyDayDeadline()
      throws Exception {
    var first = login(UUID.randomUUID().toString(), "restart@example.invalid");
    UUID user = accountId(first);
    String old = loginCookie(first);
    var expiry =
        jdbc.queryForObject(
            "SELECT expires_at FROM app.persistent_login WHERE id=?",
            java.sql.Timestamp.class,
            UUID.fromString(old.substring(0, 36)));
    var restarted = rememberedBrowser(old);
    String before = restarted.csrf();
    google.resetRequests();
    assertThat(accountId(restarted)).isEqualTo(user);
    assertThat(loginCookie(restarted)).isNotEqualTo(old);
    assertThat(
            jdbc.queryForObject(
                "SELECT expires_at FROM app.persistent_login WHERE id=?",
                java.sql.Timestamp.class,
                UUID.fromString(old.substring(0, 36))))
        .isEqualTo(expiry);
    assertThat(restarted.csrf()).isNotEmpty();
    assertThat(before).isNotEmpty();
    google.verify(0, postRequestedFor(urlEqualTo("/token")));
    assertThat(restarted.get("/api/v1/me/withdrawal-confirmation").body()).contains("false");
    assertThat(restarted.mutate("DELETE", "/api/v1/me", restarted.csrf()).statusCode())
        .isEqualTo(409);
  }

  @Test
  void expiredSessionCanSaveAPlaylistUsingItsExistingCsrfHeaderButMissingOrWrongCsrfStillFails()
      throws Exception {
    var browser = login(UUID.randomUUID().toString(), "write@example.invalid");
    UUID user = accountId(browser);
    String csrf = browser.csrf();
    expireSession(sessions, user.toString(), false);
    browser.allowSecureLocalhostCookiesForJdkTestClient();
    var result =
        browser.client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me/playlists"))
                .header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Restored session\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.playlist WHERE user_id=? AND name='Restored session'",
                Integer.class,
                user))
        .isEqualTo(1);
    for (String bad : new String[] {null, "invalid"}) {
      expireSession(sessions, user.toString(), false);
      assertThat(browser.mutate("POST", "/api/v1/me/playlists", bad).statusCode()).isEqualTo(403);
    }
  }

  @Test
  void restorationChangesAnAnonymousSessionIdAndDoesNotReviveWithdrawalProof() throws Exception {
    var first = login(UUID.randomUUID().toString(), "fixation@example.invalid");
    UUID user = accountId(first);
    String remember = loginCookie(first);
    var anonymous = new Browser();
    anonymous.csrf();
    String old = anonymous.cookie();
    var token = new java.net.HttpCookie("STELODY_LOGIN", remember);
    token.setPath("/");
    token.setDomain("localhost.local");
    token.setVersion(0);
    anonymous.cookies.getCookieStore().add(URI.create("http://localhost:" + port), token);
    assertThat(accountId(anonymous)).isEqualTo(user);
    assertThat(anonymous.cookie()).isNotEqualTo(old);
    assertThat(anonymous.mutate("DELETE", "/api/v1/me", anonymous.csrf()).statusCode())
        .isEqualTo(409);
  }

  @Test
  void replayOutsideTheConcurrentRequestGraceWindowCannotCreateASession() throws Exception {
    var first = login(UUID.randomUUID().toString(), "replay-device@example.invalid");
    String old = loginCookie(first);
    var restored = rememberedBrowser(old);
    UUID user = accountId(restored);
    var replay = rememberedBrowser(old);
    assertThat(accountId(replay)).isEqualTo(user);
    assertThat(loginCookie(replay))
        .isEqualTo(old); // Grace request never overwrites the winning cookie.
    jdbc.update(
        "UPDATE app.persistent_login SET previous_valid_until=now()-interval '1 second' WHERE id=?",
        UUID.fromString(old.substring(0, 36)));
    assertThat(rememberedBrowser(old).get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(restored.get("/api/v1/me").statusCode()).isEqualTo(200);
    String fake = old.substring(0, 37) + "A".repeat(43);
    assertThat(rememberedBrowser(fake).get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(accountId(rememberedBrowser(loginCookie(restored)))).isEqualTo(user);
  }

  @Test
  void concurrentRestorationsDoNotRotateEachOthersCredentialAway() throws Exception {
    var first = login(UUID.randomUUID().toString(), "parallel-device@example.invalid");
    String old = loginCookie(first);
    UUID user = accountId(first);
    var a = rememberedBrowser(old);
    var b = rememberedBrowser(old);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var barrier = new java.util.concurrent.CyclicBarrier(2);
      var x =
          pool.submit(
              () -> {
                barrier.await();
                return a.get("/api/v1/me");
              });
      var y =
          pool.submit(
              () -> {
                barrier.await();
                return b.get("/api/v1/me");
              });
      assertThat(x.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(y.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
    assertThat(accountId(a)).isEqualTo(user);
    assertThat(accountId(b)).isEqualTo(user);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.persistent_login WHERE user_id=?", Integer.class, user))
        .isEqualTo(1);
    assertThat(java.util.List.of(loginCookie(a), loginCookie(b))).contains(old);
  }

  @Test
  void thirtyDayExpiryEndsBothTheCredentialAndAnExistingRestoredSession() throws Exception {
    var browser = login(UUID.randomUUID().toString(), "hard-expiry@example.invalid");
    String cookie = loginCookie(browser);
    UUID id = UUID.fromString(cookie.substring(0, 36));
    var restored = rememberedBrowser(cookie);
    assertThat(restored.get("/api/v1/me").statusCode()).isEqualTo(200);
    jdbc.update(
        "UPDATE app.persistent_login SET created_at=now()-interval '31 days',expires_at=now()-interval '1 day' WHERE id=?",
        id);
    assertThat(restored.get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(rememberedBrowser(loginCookie(browser)).get("/api/v1/me").statusCode())
        .isEqualTo(401);
  }

  @Test
  void logoutRevokesThisDeviceAndAllSessionsRestoredFromItButLeavesAnotherDeviceSignedIn()
      throws Exception {
    String subject = UUID.randomUUID().toString();
    var first = login(subject, "logout-device@example.invalid");
    UUID user = accountId(first);
    String stolen = loginCookie(first);
    var concurrent = rememberedBrowser(stolen);
    assertThat(accountId(concurrent)).isEqualTo(user);
    var other = login(subject, "logout-device@example.invalid");
    assertThat(first.logout(first.csrf()).statusCode()).isEqualTo(204);
    assertThat(concurrent.get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(rememberedBrowser(stolen).get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(other.get("/api/v1/me").statusCode()).isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "SUSPENDED"})
  void accountRoleOrStatusChangesPermanentlyRevokeExistingDeviceCredentials(String change)
      throws Exception {
    String subject = UUID.randomUUID().toString();
    var browser = login(subject, "changed-account@example.invalid");
    UUID user = accountId(browser);
    String cookie = loginCookie(browser);
    if (change.equals("ADMIN"))
      owner().update("UPDATE app.app_user SET role='ADMIN' WHERE id=?", user);
    else owner().update("UPDATE app.app_user SET status='SUSPENDED' WHERE id=?", user);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.persistent_login WHERE user_id=?", Integer.class, user))
        .isZero();
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
    assertThat(rememberedBrowser(cookie).get("/api/v1/me").statusCode()).isEqualTo(401);
    owner().update("UPDATE app.app_user SET role='USER',status='ACTIVE' WHERE id=?", user);
    assertThat(rememberedBrowser(cookie).get("/api/v1/me").statusCode()).isEqualTo(401);
    if (change.equals("ADMIN")) {
      owner().update("UPDATE app.app_user SET role='ADMIN' WHERE id=?", user);
      var admin = login(subject, "changed-account@example.invalid");
      assertThat(admin.get("/api/v1/me").statusCode()).isEqualTo(200);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM app.persistent_login WHERE user_id=?", Integer.class, user))
          .isZero();
      expireSession(sessions, user.toString(), true);
      assertThat(admin.get("/api/v1/me").statusCode()).isEqualTo(401);
    }
  }

  @Test
  void accountDeletionCascadesEveryDeviceCredential() throws Exception {
    var browser = login(UUID.randomUUID().toString(), "deleted-device@example.invalid");
    UUID user = accountId(browser);
    String cookie = loginCookie(browser);
    owner().update("DELETE FROM app.app_user WHERE id=?", user);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.persistent_login WHERE user_id=?", Integer.class, user))
        .isZero();
    assertThat(rememberedBrowser(cookie).get("/api/v1/me").statusCode()).isEqualTo(401);
  }

  @Test
  void databaseOutageReturns503WithoutLosingTheDeviceCookieOrPretendingToBeSignedOut()
      throws Exception {
    var browser = login(UUID.randomUUID().toString(), "db-device@example.invalid");
    String cookie = loginCookie(browser);
    var restarted = rememberedBrowser(cookie);
    owner().execute("REVOKE SELECT ON app.persistent_login FROM stelody_app");
    try {
      var result = restarted.get("/api/v1/me");
      assertThat(result.statusCode()).isEqualTo(503);
      assertThat(result.body())
          .contains("LOGIN_STORAGE_UNAVAILABLE")
          .doesNotContain(cookie, "SELECT", "permission denied");
      assertThat(result.headers().allValues("Set-Cookie"))
          .noneMatch(c -> c.startsWith("STELODY_LOGIN="));
    } finally {
      owner().execute("GRANT SELECT ON app.persistent_login TO stelody_app");
    }
    assertThat(restarted.get("/api/v1/me").statusCode()).isEqualTo(200);
  }

  @Test
  void googleReauthenticationDoesNotExtendDeviceExpiryAndNewCsrfSurvivesSessionRestoration()
      throws Exception {
    String subject = UUID.randomUUID().toString();
    var browser = login(subject, "reauth-device@example.invalid");
    UUID user = accountId(browser);
    UUID grant = UUID.fromString(loginCookie(browser).substring(0, 36));
    var deadline =
        jdbc.queryForObject(
            "SELECT expires_at FROM app.persistent_login WHERE id=?",
            java.sql.Timestamp.class,
            grant);
    String oldCsrf = browser.csrf();
    var auth = reauthenticate(browser);
    tokens(auth, subject, "reauth-device@example.invalid", "none");
    assertThat(callback(browser, auth).statusCode()).isEqualTo(302);
    String newCsrf = browser.csrf();
    assertThat(
            jdbc.queryForObject(
                "SELECT expires_at FROM app.persistent_login WHERE id=?",
                java.sql.Timestamp.class,
                grant))
        .isEqualTo(deadline);
    assertThat(browser.logout(oldCsrf).statusCode()).isEqualTo(403);
    expireSession(sessions, user.toString(), false);
    assertThat(browser.logout(newCsrf).statusCode()).isEqualTo(204);
    assertThat(deadline.toInstant()).isBefore(Instant.now().plusSeconds(2592000));
  }

  @Test
  void logoutStorageFailureIsNotReportedAsSuccessAndCanBeRetried() throws Exception {
    var browser = login(UUID.randomUUID().toString(), "logout-outage@example.invalid");
    UUID user = accountId(browser);
    String csrf = browser.csrf();
    owner().execute("REVOKE DELETE ON app.persistent_login FROM stelody_app");
    try {
      var result = browser.logout(csrf);
      assertThat(result.statusCode()).isEqualTo(503);
      assertThat(result.body())
          .contains("LOGIN_STORAGE_UNAVAILABLE")
          .doesNotContain("permission denied", "DELETE FROM");
      assertThat(result.headers().allValues("Set-Cookie"))
          .noneMatch(c -> c.startsWith("STELODY_LOGIN="));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM app.persistent_login WHERE user_id=?", Integer.class, user))
          .isEqualTo(1);
    } finally {
      owner().execute("GRANT DELETE ON app.persistent_login TO stelody_app");
    }
    assertThat(browser.logout(csrf).statusCode()).isEqualTo(204);
    assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
  }

  @Test
  void malformedDeviceCredentialDoesNotAuthenticateOrDeleteAnExistingValidCredential()
      throws Exception {
    var browser = login(UUID.randomUUID().toString(), "malformed-device@example.invalid");
    UUID user = accountId(browser);
    String valid = loginCookie(browser);
    for (String value : new String[] {"invalid", valid.substring(0, 36), valid + "junk", ""}) {
      var result = rememberedBrowser(value).get("/api/v1/me");
      assertThat(result.statusCode()).isEqualTo(401);
      assertThat(result.headers().allValues("Set-Cookie"))
          .anySatisfy(c -> assertThat(c).contains("STELODY_LOGIN=", "Max-Age=0"));
    }
    assertThat(accountId(rememberedBrowser(valid))).isEqualTo(user);
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
