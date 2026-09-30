package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stelody.auth.domain.SessionUser;
import com.stelody.playlist.domain.PlaylistCursor;
import com.stelody.playlist.repository.PlaylistRepository;
import com.stelody.playlist.service.PlaylistService;
import com.stelody.playlist.web.PlaylistException;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class PlaylistIntegrationTest {
  static final UUID U1 = id(1), U2 = id(2), M1 = id(10), S1 = id(101), S2 = id(102), S3 = id(103);
  static final String ROOT = "/api/v1/me/playlists";
  static final AtomicInteger RELATION_QUERIES = new AtomicInteger();

  // Count only relationship hydration, excluding session and account SQL.
  @TestConfiguration
  static class QueryCounting {
    @Bean
    static BeanPostProcessor countRelations() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          return name.equals("dataSource") && bean instanceof DataSource source
              ? new CountingDataSource(source)
              : bean;
        }
      };
    }
  }

  static class CountingDataSource extends DelegatingDataSource implements AutoCloseable {
    CountingDataSource(DataSource source) {
      super(source);
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
      var connection = super.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")
                    && args[0] instanceof String sql
                    && (sql.stripLeading().startsWith("SELECT sm.song_id")
                        || sql.stripLeading().startsWith("SELECT w.id")))
                  RELATION_QUERIES.incrementAndGet();
                try {
                  return method.invoke(connection, args);
                } catch (InvocationTargetException exception) {
                  throw exception.getCause();
                }
              });
    }

    @Override
    public void close() throws Exception {
      if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
    }
  }

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "stelody_app");
    registry.add("spring.datasource.password", () -> "test-runtime");
    registry.add("spring.flyway.user", () -> "stelody_migrator");
    registry.add("spring.flyway.password", () -> "test-migrator");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate runtime;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  @Autowired PlaylistService playlists;
  @Autowired PlaylistRepository playlistRepository;
  @Autowired PlatformTransactionManager transactions;
  JdbcTemplate writer;
  Browser first, second;

  record Browser(Cookie cookie, String csrfHeader, String csrf) {}

  static UUID id(int value) {
    return new UUID(0, value);
  }

  @BeforeEach
  void fixtures() throws Exception {
    writer =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    writer.execute(
        "TRUNCATE app.app_user, app.member, app.artist, app.musical_work, app.song_entry, app.video, app.view_publication, session.spring_session CASCADE");
    writer.execute("INSERT INTO app.catalog_state(singleton) VALUES(true) ON CONFLICT DO NOTHING");
    for (UUID userId : List.of(U1, U2))
      writer.update(
          "INSERT INTO app.app_user(id, google_subject, email) VALUES (?, ?, ?)",
          userId,
          userId.toString(),
          userId + "@example.invalid");
    writer.update(
        "INSERT INTO app.member(id, name, search_name, activity_status) VALUES (?, '테스트 멤버', '테스트 멤버', 'ACTIVE')",
        M1);
    for (UUID song : List.of(S1, S2, S3)) {
      writer.update(
          "INSERT INTO app.song_entry(id, title, search_title, song_type, visibility) VALUES (?, ?, ?, 'COVER', 'PUBLISHED')",
          song,
          "Test song " + song,
          "test song " + song);
      writer.update(
          "INSERT INTO app.video(id, song_id, youtube_id, video_kind, availability, source_title, published_at, thumbnail_url) VALUES (?, ?, ?, 'OFFICIAL_COVER', 'PUBLIC', 'Private source title', '2026-01-01T00:00:00Z', 'https://example.invalid/thumbnail')",
          id(song.hashCode() + 1000),
          song,
          String.format("%011d", song.hashCode()));
      writer.update(
          "UPDATE app.song_entry SET representative_video_id = ? WHERE id = ?",
          id(song.hashCode() + 1000),
          song);
      writer.update(
          "INSERT INTO app.song_member(song_id, member_id, position) VALUES (?, ?, 0)", song, M1);
    }
    first = browser(U1);
    second = browser(U2);
  }

  Browser browser(UUID userId) throws Exception {
    return browser(sessions, userId);
  }

  <S extends Session> Browser browser(FindByIndexNameSessionRepository<S> repository, UUID userId)
      throws Exception {
    S session = repository.createSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(userId, Instant.now()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    repository.save(session);
    var cookie =
        new Cookie(
            "SESSION",
            Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    var csrf =
        json(
            mvc.perform(get("/api/v1/auth/csrf").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return new Browser(cookie, csrf.get("headerName").asText(), csrf.get("token").asText());
  }

  MockHttpServletRequestBuilder authenticated(
      MockHttpServletRequestBuilder request, Browser browser) {
    return request.cookie(browser.cookie()).header(browser.csrfHeader(), browser.csrf());
  }

  JsonNode json(String value) {
    return mapper.readTree(value);
  }

  JsonNode list(Browser browser, String query) throws Exception {
    return json(
        mvc.perform(authenticated(get(ROOT + query), browser))
            .andExpect(status().isOk())
            .andExpect(
                header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  JsonNode create(Browser browser, String name) throws Exception {
    return response(
        authenticated(post(ROOT), browser)
            .contentType("application/json")
            .content(mapper.writeValueAsString(java.util.Map.of("name", name))),
        201);
  }

  UUID uuid(JsonNode value, String field) {
    return UUID.fromString(value.get(field).asText());
  }

  JsonNode detail(Browser browser, UUID id) throws Exception {
    return response(authenticated(get(ROOT + "/" + id), browser), 200);
  }

  JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
    return json(
        mvc.perform(request)
            .andExpect(status().is(expectedStatus))
            .andExpect(
                header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  void seedPlaylist(UUID id, UUID owner, String date) {
    writer.update(
        "INSERT INTO app.playlist(id, user_id, name, created_at, updated_at) VALUES (?, ?, 'Seed playlist', ?::timestamptz, ?::timestamptz)",
        id,
        owner,
        date,
        date);
  }

  @Test
  void createReadRenameAndDeleteUseOneVersionPerChangeAndSessionOwner() throws Exception {
    var created =
        response(
            authenticated(post(ROOT).param("ownerId", U2.toString()), first)
                .contentType("application/json")
                .content(
                    """
{"name":"  나의 목록  "}
"""),
            201);
    UUID id = uuid(created, "id");
    assertThat(created.get("name").asText()).isEqualTo("나의 목록");
    assertThat(created.get("version").asLong()).isZero();
    assertThat(created.get("totalCount").asLong()).isZero();
    assertThat(created.get("availableCount").asLong()).isZero();
    assertThat(list(first, "").get("totalCount").asLong()).isEqualTo(1);
    assertThat(list(second, "?ownerId=" + U1).get("items").size()).isZero();
    mvc.perform(authenticated(get(ROOT + "/" + id), second)).andExpect(status().isNotFound());
    var renamed =
        response(
            authenticated(patch(ROOT + "/" + id), first)
                .contentType("application/json")
                .content(
                    """
{"name":"수정한 목록","version":0}
"""),
            200);
    assertThat(renamed.get("version").asLong()).isEqualTo(1);
    assertThat(renamed.get("name").asText()).isEqualTo("수정한 목록");
    mvc.perform(authenticated(delete(ROOT + "/" + id + "?version=0"), first))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PLAYLIST_CHANGED"));
    mvc.perform(authenticated(delete(ROOT + "/" + id + "?version=1"), first))
        .andExpect(status().isNoContent());
    mvc.perform(authenticated(get(ROOT + "/" + id), first)).andExpect(status().isNotFound());
    assertThat(list(first, "").get("items").size()).isZero();
  }

  @Test
  void namesKeepDisplayTextAcceptFiftyCharactersAndRejectWhitespaceOrOverflow() throws Exception {
    assertThat(create(first, "가".repeat(50)).get("name").asText()).isEqualTo("가".repeat(50));
    assertThat(create(first, "🎵".repeat(50)).get("name").asText()).isEqualTo("🎵".repeat(50));
    create(first, "중복 이름");
    create(first, "중복 이름");
    for (String name : List.of("", "  ", "\u00a0", "가".repeat(51))) {
      mvc.perform(
              authenticated(post(ROOT), first)
                  .contentType("application/json")
                  .content(mapper.writeValueAsString(java.util.Map.of("name", name))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PLAYLIST_REQUEST"));
    }
  }

  @Test
  void everyPrivateRouteChecksAuthenticationAndMutationsRequireCsrf() throws Exception {
    UUID id = uuid(create(first, "보안 확인"), "id");
    for (String route : List.of(ROOT, ROOT + "/" + id))
      mvc.perform(get(route)).andExpect(status().isUnauthorized());
    for (var request :
        List.of(
            post(ROOT).content("{\"name\":\"new\"}"),
            patch(ROOT + "/" + id).content("{\"name\":\"rename\",\"version\":0}"),
            delete(ROOT + "/" + id + "?version=0"))) {
      mvc.perform(request.contentType("application/json").cookie(first.cookie()))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            post(ROOT)
                .cookie(first.cookie())
                .header(first.csrfHeader(), second.csrf())
                .contentType("application/json")
                .content("{\"name\":\"wrong token\"}"))
        .andExpect(status().isForbidden());
    var csrfResponse = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
    var csrf = json(csrfResponse.getContentAsString());
    mvc.perform(
            post(ROOT)
                .cookie(csrfResponse.getCookies())
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .contentType("application/json")
                .content("{\"name\":\"anonymous\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(authenticated(put(ROOT + "/" + id), first)).andExpect(status().isForbidden());
    assertThat(detail(first, id).get("version").asLong()).isZero();
  }

  @Test
  void othersPlaylistsAndItemsAlwaysReturnNotFoundWithValidInputs() throws Exception {
    UUID id = uuid(create(first, "Private"), "id");
    for (var request :
        List.of(
            get(ROOT + "/" + id),
            patch(ROOT + "/" + id).content("{\"name\":\"hijack\",\"version\":0}"),
            delete(ROOT + "/" + id + "?version=0"))) {
      mvc.perform(authenticated(request.contentType("application/json"), second))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("PLAYLIST_NOT_FOUND"));
    }
    assertThat(detail(first, id).get("version").asLong()).isZero();
  }

  @Test
  void listCursorsUseCreationTimeAndIdAndRejectOtherAccounts() throws Exception {
    for (UUID id : List.of(id(1001), id(1002), id(1003)))
      seedPlaylist(id, U1, "2026-01-01T00:00:00.123456Z");
    seedPlaylist(id(1004), U2, "2026-01-01T00:00:00.123456Z");
    var page = list(first, "?size=1");
    assertThat(uuid(page.get("items").get(0), "id")).isEqualTo(id(1003));
    String cursor = page.get("nextCursor").asText();
    mvc.perform(authenticated(get(ROOT).param("cursor", cursor), second))
        .andExpect(status().isBadRequest());
    page = list(first, "?size=50&cursor=" + cursor);
    assertThat(page.get("items").size()).isEqualTo(2);
    assertThat(uuid(page.get("items").get(0), "id")).isEqualTo(id(1002));
    assertThat(uuid(page.get("items").get(1), "id")).isEqualTo(id(1001));
    assertThat(page.get("nextCursor").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"?size=0", "?size=51", "?size=x", "?cursor=bad", "/bad-id"})
  void invalidReadInputsUseProblemResponses(String suffix) throws Exception {
    mvc.perform(authenticated(get(ROOT + suffix), first))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PLAYLIST_REQUEST"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void invalidMutationBodiesAndMissingVersionDoNotChangeThePlaylist() throws Exception {
    UUID id = uuid(create(first, "Validation"), "id");
    for (String body :
        List.of(
            """
{}
""",
            """
{"name":null,"version":0}
""",
            """
{"name":"change"}
""",
            """
{"name":"change","version":-1}
""",
            """
{"name":"change","version":"bad"}
""",
            "{")) {
      mvc.perform(
              authenticated(patch(ROOT + "/" + id), first)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PLAYLIST_REQUEST"));
    }
    mvc.perform(authenticated(delete(ROOT + "/" + id), first)).andExpect(status().isBadRequest());
    mvc.perform(authenticated(delete(ROOT + "/" + id + "?version=-1"), first))
        .andExpect(status().isBadRequest());
    assertThat(detail(first, id).get("version").asLong()).isZero();
  }

  List<Integer> concurrent(List<Callable<Integer>> tasks) throws Exception {
    var start = new CyclicBarrier(tasks.size());
    try (var pool = Executors.newFixedThreadPool(tasks.size())) {
      var futures =
          tasks.stream()
              .map(
                  task ->
                      pool.submit(
                          () -> {
                            start.await(10, TimeUnit.SECONDS);
                            try {
                              return task.call();
                            } catch (PlaylistException exception) {
                              return exception.status();
                            } catch (OptimisticLockingFailureException exception) {
                              return 409;
                            }
                          }))
              .toList();
      var statuses = new ArrayList<Integer>();
      for (var future : futures) statuses.add(future.get(20, TimeUnit.SECONDS));
      return statuses;
    }
  }

  @Test
  void concurrentCreatesAtTheFiftyPlaylistLimitAllowOnlyOne() throws Exception {
    writer.update(
        """
        INSERT INTO app.playlist(id, user_id, name, created_at, updated_at)
        SELECT md5('limit-playlist-' || n)::uuid, ?, 'Limit playlist', now(), now()
        FROM generate_series(1, 49) n
        """,
        U1);
    assertThat(
            concurrent(
                List.of(
                    () -> {
                      playlists.create(U1, "first");
                      return 201;
                    },
                    () -> {
                      playlists.create(U1, "second");
                      return 201;
                    })))
        .containsExactlyInAnyOrder(201, 409);
    assertThat(list(first, "").get("totalCount").asLong()).isEqualTo(50);
    mvc.perform(
            authenticated(post(ROOT), first)
                .contentType("application/json")
                .content("{\"name\":\"over capacity\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PLAYLIST_LIMIT_REACHED"));
    create(second, "다른 회원");
  }

  @Test
  void actualVersionGuardRejectsOneOfTwoTransactionsThatLoadedTheSameVersion() throws Exception {
    UUID id = uuid(create(first, "낙관적 잠금"), "id");
    var bothLoaded = new CyclicBarrier(2);
    Callable<Integer> rename =
        () ->
            new TransactionTemplate(transactions)
                .execute(
                    transaction -> {
                      var playlist = playlistRepository.findByIdAndUserId(id, U1).orElseThrow();
                      try {
                        bothLoaded.await(10, TimeUnit.SECONDS);
                      } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                      }
                      playlist.rename(Thread.currentThread().getName());
                      playlist.touch();
                      playlistRepository.flush();
                      return 200;
                    });
    assertThat(concurrent(List.of(rename, rename))).containsExactlyInAnyOrder(200, 409);
    assertThat(detail(first, id).get("version").asLong()).isEqualTo(1);
  }

  @Test
  void suspendedAndExpiredSessionsCannotReadOrChangePlaylists() throws Exception {
    UUID id = uuid(create(first, "세션"), "id");
    writer.update("UPDATE app.app_user SET status = 'SUSPENDED' WHERE id = ?", U1);
    mvc.perform(
            authenticated(post(ROOT), first)
                .contentType("application/json")
                .content("{\"name\":\"blocked\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(sessions.findByPrincipalName(U1.toString())).isEmpty();
    expire(sessions, U2);
    mvc.perform(authenticated(get(ROOT), second)).andExpect(status().isUnauthorized());
    assertThat(
            runtime.queryForObject("SELECT version FROM app.playlist WHERE id = ?", Long.class, id))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-01-01T00:00:00.123456789Z",
        "+999999999-01-01T00:00:00Z",
        "0000-01-01T00:00:00Z"
      })
  void invalidCursorDatesAreRejectedBeforeJdbc(String date) throws Exception {
    String cursor =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mapper.writeValueAsBytes(
                    new PlaylistCursor.ListPosition(1, U1, Instant.parse(date), id(999))));
    mvc.perform(authenticated(get(ROOT).param("cursor", cursor), first))
        .andExpect(status().isBadRequest());
  }

  <S extends Session> void expire(FindByIndexNameSessionRepository<S> repository, UUID userId) {
    S session = repository.findByPrincipalName(userId.toString()).values().iterator().next();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(userId, Instant.now().minusSeconds(43201)),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    repository.save(session);
  }
}
