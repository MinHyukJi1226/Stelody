package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stelody.auth.domain.SessionUser;
import com.stelody.favorite.domain.FavoriteCursor;
import com.stelody.favorite.service.FavoriteService;
import com.stelody.favorite.web.FavoriteException;
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
import org.springframework.dao.DataAccessException;
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
class FavoriteIntegrationTest {
  static final UUID U1 = id(1), U2 = id(2), M1 = id(10), S1 = id(101), S2 = id(102), S3 = id(103);
  static final String ROOT = "/api/v1/me/favorites";
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
  @Autowired FavoriteService favorites;
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

  void save(Browser browser, UUID song) throws Exception {
    mvc.perform(authenticated(put(ROOT + "/" + song), browser)).andExpect(status().isNoContent());
  }

  void stored(UUID user, UUID song, String date) {
    writer.update(
        "INSERT INTO app.favorite(user_id, song_id, created_at) VALUES (?, ?, ?::timestamptz)",
        user,
        song,
        date);
  }

  @Test
  void idempotentSaveAndDeletePreserveTimestampAndOrder() throws Exception {
    assertThat(list(first, "").get("totalCount").asLong()).isZero();
    stored(U1, S1, "2026-01-01T00:00:00Z");
    stored(U1, S2, "2026-01-02T00:00:00Z");
    save(first, S1);
    save(first, S1);
    var page = list(first, "");
    assertThat(page.get("totalCount").asLong()).isEqualTo(2);
    assertThat(page.get("items").get(0).get("songId").asText()).isEqualTo(S2.toString());
    assertThat(page.get("items").get(1).get("savedAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
    for (int i = 0; i < 2; i++)
      mvc.perform(authenticated(delete(ROOT + "/" + S1), first)).andExpect(status().isNoContent());
    mvc.perform(authenticated(delete(ROOT + "/" + id(999)), first))
        .andExpect(status().isNoContent());
    mvc.perform(authenticated(get(ROOT + "/" + S1), first))
        .andExpect(jsonPath("$.favorited").value(false));
  }

  @Test
  void newSaveUsesSessionOwnerAndSeparateAccountsCannotReadOrDeleteIt() throws Exception {
    mvc.perform(authenticated(put(ROOT + "/" + S1).param("ownerId", U2.toString()), first))
        .andExpect(status().isNoContent());
    assertThat(list(first, "").get("items").size()).isEqualTo(1);
    assertThat(list(second, "?ownerId=" + U1).get("items").size()).isZero();
    mvc.perform(authenticated(get(ROOT + "/" + S1), second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.favorited").value(false));
    mvc.perform(authenticated(delete(ROOT + "/" + S1), second)).andExpect(status().isNoContent());
    mvc.perform(authenticated(get(ROOT + "/" + S1), first))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.favorited").value(true));
    save(second, S1);
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.favorite", Long.class))
        .isEqualTo(2);
  }

  @Test
  void privateRoutesNeedAuthenticationAndMutationsNeedTheSessionCsrfToken() throws Exception {
    mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
    mvc.perform(get(ROOT + "/" + S1)).andExpect(status().isUnauthorized());
    var anonymousCsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
    var token = json(anonymousCsrf.getContentAsString());
    for (var request : List.of(put(ROOT + "/" + S1), delete(ROOT + "/" + S1))) {
      mvc.perform(
              request
                  .cookie(anonymousCsrf.getCookies())
                  .header(token.get("headerName").asText(), token.get("token").asText()))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(put(ROOT + "/" + S1).cookie(first.cookie())).andExpect(status().isForbidden());
    mvc.perform(delete(ROOT + "/" + S1).cookie(first.cookie())).andExpect(status().isForbidden());
    mvc.perform(
            put(ROOT + "/" + S1).cookie(first.cookie()).header(first.csrfHeader(), second.csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(authenticated(post(ROOT), first)).andExpect(status().isForbidden());
    assertThat(list(first, "").get("totalCount").asLong()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "HIDDEN",
        "DRAFT",
        "PRIVATE",
        "DELETED",
        "UNAVAILABLE",
        "UNLISTED",
        "NO_VIDEO",
        "UNCONFIRMED",
        "NO_DATE"
      })
  void unavailableSavedSongsKeepOnlyThePlaceholderAndCanBeRemoved(String condition)
      throws Exception {
    save(first, S1);
    save(first, S2);
    switch (condition) {
      case "HIDDEN", "DRAFT" ->
          writer.update("UPDATE app.song_entry SET visibility = ? WHERE id = ?", condition, S1);
      case "NO_VIDEO" ->
          writer.update(
              "UPDATE app.song_entry SET representative_video_id = NULL WHERE id = ?", S1);
      case "UNCONFIRMED" ->
          writer.update("UPDATE app.song_member SET confirmed = false WHERE song_id = ?", S1);
      case "NO_DATE" ->
          writer.update("UPDATE app.video SET published_at = NULL WHERE song_id = ?", S1);
      default ->
          writer.update("UPDATE app.video SET availability = ? WHERE song_id = ?", condition, S1);
    }
    save(first, S1); // A retry of an existing save remains successful after availability changes.
    var page = list(first, "");
    assertThat(page.get("totalCount").asLong()).isEqualTo(2);
    assertThat(page.get("availableCount").asLong()).isEqualTo(1);
    JsonNode placeholder = null;
    for (var item : page.get("items"))
      if (item.get("songId").asText().equals(S1.toString())) placeholder = item;
    assertThat(placeholder.get("available").asBoolean()).isFalse();
    assertThat(placeholder.get("song").isNull()).isTrue();
    assertThat(placeholder.get("unavailableMessage").asText()).isEqualTo("현재 이용할 수 없는 곡");
    assertThat(page.toString())
        .doesNotContain(
            "Test song " + S1, "Private source title", String.format("%011d", S1.hashCode()));
    mvc.perform(authenticated(put(ROOT + "/" + S1), second)).andExpect(status().isNotFound());
    mvc.perform(authenticated(delete(ROOT + "/" + S1), first)).andExpect(status().isNoContent());
    assertThat(list(first, "").get("totalCount").asLong()).isEqualTo(1);
  }

  @Test
  void missingSongSaveHasSameNotFoundResponseAsHiddenSong() throws Exception {
    mvc.perform(authenticated(put(ROOT + "/" + id(999)), first))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CATALOG_NOT_FOUND"));
    assertThat(list(first, "").get("items").size()).isZero();
  }

  @Test
  void savedDateAndIdCursorsHaveNoTieDuplicatesAndCannotCrossAccounts() throws Exception {
    for (var song : List.of(S1, S2, S3)) stored(U1, song, "2026-01-01T00:00:00.123456Z");
    var page = list(first, "?size=1");
    assertThat(page.get("items").get(0).get("songId").asText()).isEqualTo(S3.toString());
    String cursor = page.get("nextCursor").asText();
    mvc.perform(authenticated(get(ROOT).param("cursor", cursor), second))
        .andExpect(status().isBadRequest());
    mvc.perform(authenticated(delete(ROOT + "/" + S3), first)).andExpect(status().isNoContent());
    save(first, S3); // A later insert above the cursor is seen when refreshing the first page.
    page = list(first, "?size=50&cursor=" + cursor);
    assertThat(page.get("items").size()).isEqualTo(2);
    assertThat(page.get("items").get(0).get("songId").asText()).isEqualTo(S2.toString());
    assertThat(page.get("items").get(1).get("songId").asText()).isEqualTo(S1.toString());
    assertThat(page.get("hasNext").asBoolean()).isFalse();
    assertThat(page.get("nextCursor").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"?size=0", "?size=51", "?size=x", "?cursor=", "?cursor=bad", "/not-a-uuid"})
  void invalidQueriesHaveSafeProblemResponses(String suffix) throws Exception {
    mvc.perform(authenticated(get(ROOT + suffix), first))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_FAVORITE_QUERY"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-01-01T00:00:00.123456789Z",
        "+999999999-01-01T00:00:00Z",
        "0000-01-01T00:00:00Z"
      })
  void fabricatedCursorDatesAreRejectedBeforeJdbcConversion(String date) throws Exception {
    var cursor =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mapper.writeValueAsBytes(
                    new FavoriteCursor.Position(1, U1, Instant.parse(date), S1)));
    mvc.perform(authenticated(get(ROOT).param("cursor", cursor), first))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_FAVORITE_QUERY"));
  }

  @Test
  void suspendedAndAbsolutelyExpiredSessionsCannotUseFavorites() throws Exception {
    writer.update("UPDATE app.app_user SET status = 'SUSPENDED' WHERE id = ?", U1);
    assertThatThrownBy(() -> favorites.save(U1, S1))
        .isInstanceOfSatisfying(
            FavoriteException.class, exception -> assertThat(exception.status()).isEqualTo(401));
    mvc.perform(authenticated(put(ROOT + "/" + S1), first)).andExpect(status().isUnauthorized());
    assertThat(sessions.findByPrincipalName(U1.toString())).isEmpty();
    expire(sessions, U2);
    mvc.perform(authenticated(get(ROOT), second)).andExpect(status().isUnauthorized());
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.favorite", Long.class)).isZero();
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

  List<Integer> concurrent(List<UUID> songIds) throws Exception {
    var barrier = new CyclicBarrier(songIds.size());
    try (var pool = Executors.newFixedThreadPool(songIds.size())) {
      var futures =
          songIds.stream()
              .map(
                  song ->
                      pool.submit(
                          () -> {
                            barrier.await(10, TimeUnit.SECONDS);
                            try {
                              favorites.save(U1, song);
                              return 204;
                            } catch (FavoriteException exception) {
                              return exception.status();
                            }
                          }))
              .toList();
      var statuses = new ArrayList<Integer>();
      for (var future : futures) statuses.add(future.get(20, TimeUnit.SECONDS));
      return statuses;
    }
  }

  @Test
  void concurrentDuplicateSavesProduceOneRowAndAllSucceed() throws Exception {
    assertThat(concurrent(List.of(S1, S1, S1, S1))).containsOnly(204);
    assertThat(list(first, "").get("totalCount").asLong()).isEqualTo(1);
  }

  @Test
  void concurrentSavesAtDefaultLimitAllowExactlyOneNewSongAndDeletesFreeCapacity()
      throws Exception {
    writer.execute(
        """
        INSERT INTO app.song_entry(id, title, search_title, song_type)
          SELECT md5('limit-song-' || n)::uuid, 'Limit Song', 'limit song', 'COVER'
          FROM generate_series(1, 4999) n
        """);
    writer.update(
        "INSERT INTO app.favorite(user_id, song_id) SELECT ?, id FROM app.song_entry WHERE search_title = 'limit song'",
        U1);
    assertThat(concurrent(List.of(S1, S2))).containsExactlyInAnyOrder(204, 409);
    var page = list(first, "");
    assertThat(page.get("totalCount").asLong()).isEqualTo(5000);
    UUID saved =
        runtime.queryForObject(
            "SELECT song_id FROM app.favorite WHERE user_id = ? AND song_id IN (?, ?)",
            UUID.class,
            U1,
            S1,
            S2);
    save(first, saved); // Idempotency also holds at capacity.
    mvc.perform(authenticated(put(ROOT + "/" + S3), first))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("FAVORITE_LIMIT_REACHED"));
    save(second, S3); // Other accounts retain their own capacity.
    mvc.perform(authenticated(delete(ROOT + "/" + saved), first)).andExpect(status().isNoContent());
    save(first, S3);
    assertThat(list(first, "").get("totalCount").asLong()).isEqualTo(5000);
  }

  @Test
  void twentyAndFiftyItemsUseOneRelationshipHydrationQuery() throws Exception {
    writer.execute(
        """
        INSERT INTO app.song_entry(id, title, search_title, song_type, visibility)
          SELECT md5('page-song-' || n)::uuid, 'Page Song', 'page song', 'COVER', 'PUBLISHED'
          FROM generate_series(1, 50) n
        """);
    writer.execute(
        """
        INSERT INTO app.video(id, song_id, youtube_id, video_kind, availability, source_title, published_at)
          SELECT md5('page-video-' || n)::uuid, md5('page-song-' || n)::uuid, lpad((10000 + n)::text, 11, '0'),
            'OFFICIAL_COVER', 'PUBLIC', 'Page source', '2026-01-01T00:00:00Z'::timestamptz
          FROM generate_series(1, 50) n
        """);
    writer.execute(
        "UPDATE app.song_entry s SET representative_video_id = v.id FROM app.video v WHERE s.id = v.song_id AND s.search_title = 'page song'");
    writer.update(
        "INSERT INTO app.song_member(song_id, member_id, position) SELECT id, ?, 0 FROM app.song_entry WHERE search_title = 'page song'",
        M1);
    writer.update(
        "INSERT INTO app.favorite(user_id, song_id) SELECT ?, id FROM app.song_entry WHERE search_title = 'page song'",
        U1);
    for (int size : List.of(20, 50)) {
      RELATION_QUERIES.set(0);
      assertThat(list(first, "?size=" + size).get("items").size()).isEqualTo(size);
      assertThat(RELATION_QUERIES.get()).isEqualTo(1);
    }
  }

  @Test
  void runtimePrivilegesUniqueForeignKeysAndAccountCascadeAreEnforced() throws Exception {
    save(first, S1);
    assertThatThrownBy(
            () ->
                runtime.update("UPDATE app.favorite SET created_at = now() WHERE user_id = ?", U1))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> writer.update("INSERT INTO app.favorite(user_id, song_id) VALUES (?, ?)", U1, S1))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                writer.update(
                    "INSERT INTO app.favorite(user_id, song_id) VALUES (?, ?)", U1, id(999)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> writer.update("DELETE FROM app.song_entry WHERE id = ?", S1))
        .isInstanceOf(DataAccessException.class);
    writer.update("DELETE FROM app.app_user WHERE id = ?", U1);
    assertThat(runtime.queryForObject("SELECT count(*) FROM app.favorite", Long.class)).isZero();
  }
}
