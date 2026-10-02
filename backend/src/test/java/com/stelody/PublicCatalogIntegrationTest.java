package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stelody.catalog.domain.SearchText;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
class PublicCatalogIntegrationTest {
  static final AtomicInteger SELECTS = new AtomicInteger();

  @TestConfiguration
  static class QueryCounting {
    @Bean
    static BeanPostProcessor countQueries() {
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
      return count(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
      return count(super.getConnection(username, password));
    }

    private Connection count(Connection connection) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")
                    && args[0] instanceof String sql
                    && (sql.stripLeading().startsWith("SELECT")
                        || sql.stripLeading().startsWith("WITH"))) SELECTS.incrementAndGet();
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
  JdbcTemplate writer;
  static final UUID M1 = id(1), M2 = id(2), M3 = id(3), W1 = id(10), A1 = id(20);
  static final UUID S1 = id(101), S2 = id(102), S3 = id(103), S4 = id(104);

  static UUID id(int number) {
    return new UUID(0, number);
  }

  @BeforeEach
  void fixtures() {
    writer =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    writer.execute(
        "TRUNCATE app.member, app.artist, app.musical_work, app.song_entry, app.video, app.view_publication CASCADE");
    writer.execute("INSERT INTO app.catalog_state(singleton) VALUES (true) ON CONFLICT DO NOTHING");
    member(M1, "가수 Alpha", "ACTIVE");
    member(M2, "가수 Beta", "GRADUATED");
    member(M3, "가수 Empty", "ACTIVE");
    writer.update(
        "INSERT INTO app.member_alias VALUES (?, ?, ?)", M1, "알파", SearchText.normalize("알파"));
    writer.update(
        "INSERT INTO app.channel VALUES (?, ?, ?, ?, 'MEMBER', false)",
        id(30),
        "UC" + "A".repeat(22),
        "Official Alpha",
        M1);
    writer.update(
        "INSERT INTO app.artist VALUES (?, ?, ?)",
        A1,
        "외부 Singer",
        SearchText.normalize("외부 Singer"));
    writer.update(
        "INSERT INTO app.artist_alias VALUES (?, ?, ?)",
        A1,
        "외부 가수",
        SearchText.normalize("외부 가수"));
    writer.update(
        "INSERT INTO app.musical_work(id, title, search_title) VALUES (?, ?, ?)",
        W1,
        "ノンブレス・オブリージュ",
        SearchText.normalize("ノンブレス・オブリージュ"));
    writer.update(
        "INSERT INTO app.work_alias VALUES (?, ?, ?)",
        W1,
        "논브레스 오블리주",
        SearchText.normalize("논브레스 오블리주"));
    writer.update("INSERT INTO app.work_artist VALUES (?, ?, 0)", W1, A1);
    song(S1, "Ｓｋｙ", "COVER", W1, "2026-01-01T00:00:00Z", M1);
    writer.update("INSERT INTO app.song_member VALUES (?, ?, 1, true)", S1, M2);
    writer.update("INSERT INTO app.song_external_artist VALUES (?, ?, 0, true)", S1, A1);
    writer.update("INSERT INTO app.song_alias VALUES (?, ?, ?)", S1, "Sky", "sky");
    song(S2, "Blue Sky", "COVER", W1, "2026-01-02T00:00:00Z", M2);
    song(S3, "Star", "ORIGINAL", null, "2026-01-03T00:00:00Z", M1);
    writer.update("UPDATE app.video SET video_kind = 'AUDIO' WHERE song_id = ?", S3);
    writer.update("INSERT INTO app.song_external_artist VALUES (?, ?, 0, true)", S3, A1);
    song(S4, "Song 100%_!", "COVER", null, "2026-01-04T00:00:00Z", M2);
    song(id(105), "Hidden Sky", "COVER", W1, "2026-01-05T00:00:00Z", M1);
    writer.update("UPDATE app.song_entry SET visibility = 'HIDDEN' WHERE id = ?", id(105));
    song(id(106), "Private Sky", "COVER", W1, "2026-01-06T00:00:00Z", M1);
    writer.update("UPDATE app.video SET availability = 'PRIVATE' WHERE song_id = ?", id(106));
    song(id(107), "Unknown Sky", "COVER", null, "2026-01-07T00:00:00Z", M1);
    writer.update("UPDATE app.song_member SET confirmed = false WHERE song_id = ?", id(107));
    song(id(108), "No representative", "COVER", null, "2026-01-08T00:00:00Z", M1);
    writer.update("UPDATE app.song_entry SET representative_video_id = null WHERE id = ?", id(108));
  }

  void member(UUID id, String name, String status) {
    writer.update(
        "INSERT INTO app.member(id, name, search_name, activity_status) VALUES (?, ?, ?, ?)",
        id,
        name,
        SearchText.normalize(name),
        status);
  }

  void song(UUID id, String title, String type, UUID work, String published, UUID member) {
    writer.update(
        "INSERT INTO app.song_entry(id, title, search_title, song_type, work_id, visibility) VALUES (?, ?, ?, ?, ?, 'PUBLISHED')",
        id,
        title,
        SearchText.normalize(title),
        type,
        work);
    writer.update(
        "INSERT INTO app.video(id, song_id, youtube_id, video_kind, availability, source_title, published_at) VALUES (?, ?, ?, ?, 'PUBLIC', ?, ?::timestamptz)",
        id(id.hashCode() + 1000),
        id,
        String.format("%011d", id.hashCode()),
        type.equals("ORIGINAL") ? "OFFICIAL_MV" : "OFFICIAL_COVER",
        "Source " + title,
        published);
    writer.update(
        "UPDATE app.song_entry SET representative_video_id = ? WHERE id = ?",
        id(id.hashCode() + 1000),
        id);
    writer.update("INSERT INTO app.song_member VALUES (?, ?, 0, true)", id, member);
  }

  JsonNode request(String path) throws Exception {
    var response = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
    return mapper.readTree(response.getContentAsString());
  }

  List<String> ids(JsonNode page) {
    var values = new ArrayList<String>();
    page.get("items").forEach(row -> values.add(row.get("id").asText()));
    return values;
  }

  @Test
  void anonymousReadsExposeOnlyPublicSongsAndKeepPrivateRoutesClosed() throws Exception {
    assertThat(ids(request("/api/v1/songs")))
        .containsExactly(S4.toString(), S3.toString(), S2.toString(), S1.toString());
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/songs")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/songs")).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/songs/" + id(105)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CATALOG_NOT_FOUND"));
    mvc.perform(get("/api/v1/songs/" + id(106))).andExpect(status().isNotFound());
  }

  @Test
  void aliasesWidthNormalizationAndRelevanceDoNotDuplicateSongs() throws Exception {
    for (String text : List.of("sky", " ＳＫＹ ")) {
      var response =
          mvc.perform(get("/api/v1/songs").param("q", text))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse();
      assertThat(ids(mapper.readTree(response.getContentAsString())))
          .containsExactly(S1.toString(), S2.toString());
    }
    for (String text : List.of("논브레스 오블리주", "ノンブレス・オブリージュ", "외부 가수")) {
      var response =
          mvc.perform(get("/api/v1/songs").param("q", text))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse();
      var found = ids(mapper.readTree(response.getContentAsString()));
      assertThat(found).contains(S1.toString(), S2.toString()).doesNotHaveDuplicates();
    }
    assertThat(ids(request("/api/v1/songs?q=알파"))).containsExactly(S3.toString(), S1.toString());
  }

  @Test
  void literalWildcardsDoNotExpandSearchAndBlankSearchIsSeparateFromBrowse() throws Exception {
    for (String text : List.of("%", "_", "!", "100%_!")) {
      var response =
          mvc.perform(get("/api/v1/songs").param("q", text))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse();
      assertThat(ids(mapper.readTree(response.getContentAsString())))
          .containsExactly(S4.toString());
    }
    assertThat(ids(request("/api/v1/songs?q=' OR 1=1 --"))).isEmpty();
    assertThat(ids(request("/api/v1/songs?q="))).isEmpty();
    assertThat(ids(request("/api/v1/songs?mode=BROWSE"))).hasSize(4);
  }

  @Test
  void memberOrFiltersAndExternalCollaborationMatchCounts() throws Exception {
    assertThat(ids(request("/api/v1/songs?memberIds=" + M1 + "," + M2)))
        .hasSize(4)
        .doesNotHaveDuplicates();
    assertThat(ids(request("/api/v1/songs?memberIds=" + M1 + "&type=ORIGINAL&collaboration=true")))
        .containsExactly(S3.toString());
    var member = request("/api/v1/members/" + M1).get("member");
    assertThat(member.get("songCounts").get("total").asLong()).isEqualTo(2);
    assertThat(member.get("songCounts").get("originals").asLong()).isEqualTo(1);
    assertThat(member.get("songCounts").get("covers").asLong()).isEqualTo(1);
    assertThat(member.get("songCounts").get("collaborations").asLong()).isEqualTo(2);
    assertThat(ids(request("/api/v1/members/" + M1 + "/songs"))).hasSize(2);
    assertThat(ids(request("/api/v1/members/" + M1 + "/songs?type=COVER&collaboration=true")))
        .containsExactly(S1.toString());
  }

  @Test
  void detailKeepsWorksVersionsAndUploadsSeparateAndOnlyReturnsConfirmedParticipants()
      throws Exception {
    var detail = request("/api/v1/songs/" + S1);
    assertThat(detail.get("song").get("participants").size()).isEqualTo(3);
    assertThat(detail.get("song").get("work").get("id").asText()).isEqualTo(W1.toString());
    assertThat(detail.get("song").get("work").get("artists").size()).isEqualTo(1);
    var related = new ArrayList<String>();
    detail.get("relatedSongs").forEach(s -> related.add(s.get("id").asText()));
    assertThat(related)
        .contains(S2.toString())
        .doesNotContain(S1.toString(), id(105).toString(), id(106).toString());
    assertThat(request("/api/v1/songs/" + S3).get("song").get("work").isNull()).isTrue();
    assertThat(request("/api/v1/songs/" + S3).get("representativeVideo").get("kind").asText())
        .isEqualTo("AUDIO");
    writer.update(
        "INSERT INTO app.video(id, song_id, youtube_id, video_kind, availability, source_title, published_at) VALUES (?, ?, '99999999999', 'REUPLOAD', 'PUBLIC', 'Reupload', '2026-02-01T00:00:00Z')",
        id(999),
        S1);
    assertThat(ids(request("/api/v1/songs"))).hasSize(4);
    assertThat(request("/api/v1/songs/" + S1).get("song").get("publishedAt").asText())
        .startsWith("2026-01-01");
    writer.update("UPDATE app.video SET embeddable = false WHERE song_id = ?", S1);
    assertThat(
            request("/api/v1/songs/" + S1).get("representativeVideo").get("embeddable").asBoolean())
        .isFalse();
  }

  @Test
  void publicStateChangesRemoveSongsFromListsDetailsRelationsAndCounts() throws Exception {
    writer.update("UPDATE app.video SET availability = 'UNAVAILABLE' WHERE song_id = ?", S1);
    assertThat(ids(request("/api/v1/songs"))).doesNotContain(S1.toString());
    mvc.perform(get("/api/v1/songs/" + S1)).andExpect(status().isNotFound());
    var related = request("/api/v1/songs/" + S2).get("relatedSongs");
    assertThat(related.toString()).doesNotContain(S1.toString());
    assertThat(
            request("/api/v1/members/" + M1).get("member").get("songCounts").get("total").asLong())
        .isEqualTo(1);
    writer.update("UPDATE app.song_entry SET visibility = 'HIDDEN' WHERE id = ?", S1);
    writer.update("UPDATE app.video SET availability = 'PUBLIC' WHERE song_id = ?", S1);
    mvc.perform(get("/api/v1/songs/" + S1)).andExpect(status().isNotFound());
  }

  @Test
  void cursorsHandleTiesFiltersAndNewUploadsWithoutOffsetDuplicates() throws Exception {
    writer.update(
        "UPDATE app.video SET published_at = '2026-01-01T00:00:00Z' WHERE song_id IN (?, ?, ?, ?)",
        S1,
        S2,
        S3,
        S4);
    String cursor = null;
    var all = new ArrayList<String>();
    do {
      var request = get("/api/v1/songs").param("size", "1");
      if (cursor != null) request.param("cursor", cursor);
      var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
      var page = mapper.readTree(response.getContentAsString());
      all.addAll(ids(page));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
      if (all.size() == 1) {
        mvc.perform(get("/api/v1/songs").param("cursor", cursor).param("type", "COVER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CATALOG_QUERY"));
        song(id(200), "New arrival", "COVER", null, "2026-02-01T00:00:00Z", M1);
      }
    } while (cursor != null);
    assertThat(all).containsExactly(S4.toString(), S3.toString(), S2.toString(), S1.toString());
    var first = request("/api/v1/songs?q=sky&size=1");
    var secondResponse =
        mvc.perform(
                get("/api/v1/songs")
                    .param("q", "sky")
                    .param("size", "1")
                    .param("cursor", first.get("nextCursor").asText()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(ids(mapper.readTree(secondResponse.getContentAsString())))
        .containsExactly(S2.toString());
  }

  UUID publish(Long s1, Long s2, Long s3, Long s4) {
    var generation = UUID.randomUUID();
    writer.update("INSERT INTO app.view_publication VALUES (?, current_timestamp)", generation);
    var counts = new Long[] {s1, s2, s3, s4};
    var songs = List.of(S1, S2, S3, S4);
    for (int i = 0; i < counts.length; i++)
      if (counts[i] != null)
        writer.update(
            "INSERT INTO app.published_video_view VALUES (?, ?, ?, current_timestamp)",
            generation,
            id(songs.get(i).hashCode() + 1000),
            counts[i]);
    writer.update("UPDATE app.catalog_state SET view_publication_id = ?", generation);
    return generation;
  }

  @Test
  void viewSortUsesOnlyRepresentativeCompletedPublicationAndRejectsStaleCursor() throws Exception {
    assertThat(request("/api/v1/songs").get("items").get(0).get("viewCount").isNull()).isTrue();
    var activePublication = publish(10L, 10L, 0L, null);
    writer.update(
        """
        INSERT INTO app.video(id, song_id, youtube_id, video_kind, availability, source_title, published_at)
        VALUES (?, ?, '99999999999', 'REUPLOAD', 'PUBLIC', 'Another upload', '2026-02-01T00:00:00Z')
        """,
        id(999),
        S1);
    writer.update(
        "INSERT INTO app.published_video_view VALUES (?, ?, 99999, current_timestamp)",
        activePublication,
        id(999));
    var pending = UUID.randomUUID();
    writer.update("INSERT INTO app.view_publication VALUES (?, current_timestamp)", pending);
    writer.update(
        "INSERT INTO app.published_video_view VALUES (?, ?, 99999, current_timestamp)",
        pending,
        id(S3.hashCode() + 1000));
    assertThat(request("/api/v1/songs/" + S3).get("song").get("viewCount").asLong()).isZero();
    String cursor = null;
    var all = new ArrayList<String>();
    do {
      var req = get("/api/v1/songs").param("sort", "VIEWS").param("size", "1");
      if (cursor != null) req.param("cursor", cursor);
      var page =
          mapper.readTree(
              mvc.perform(req)
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      all.addAll(ids(page));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
    } while (cursor != null);
    assertThat(all).containsExactly(S2.toString(), S1.toString(), S3.toString(), S4.toString());
    var first = request("/api/v1/songs?sort=VIEWS&size=1");
    publish(100L, 50L, 0L, null);
    mvc.perform(
            get("/api/v1/songs")
                .param("sort", "VIEWS")
                .param("cursor", first.get("nextCursor").asText()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VIEW_PUBLICATION_CHANGED"));
  }

  @Test
  void yearFilteringUsesKoreanCalendarBoundary() throws Exception {
    writer.update(
        "UPDATE app.video SET published_at = '2025-12-31T15:00:00Z' WHERE song_id = ?", S1);
    writer.update(
        "UPDATE app.video SET published_at = '2025-12-31T14:59:59Z' WHERE song_id = ?", S2);
    assertThat(ids(request("/api/v1/songs?year=2026")))
        .contains(S1.toString())
        .doesNotContain(S2.toString());
    assertThat(ids(request("/api/v1/songs?year=2025"))).containsExactly(S2.toString());
  }

  @Test
  void graduatedAndEmptyMembersAreDiscoverableAndPaginated() throws Exception {
    assertThat(request("/api/v1/members/" + M2).get("member").get("activityStatus").asText())
        .isEqualTo("GRADUATED");
    assertThat(ids(request("/api/v1/members/" + M3 + "/songs"))).isEmpty();
    assertThat(
            request("/api/v1/members/" + M3).get("member").get("songCounts").get("total").asLong())
        .isZero();
    assertThat(request("/api/v1/members/" + M1).get("channels").size()).isEqualTo(1);
    String cursor = null;
    var all = new ArrayList<String>();
    do {
      var req = get("/api/v1/members").param("size", "1");
      if (cursor != null) req.param("cursor", cursor);
      var page =
          mapper.readTree(
              mvc.perform(req)
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      all.addAll(ids(page));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
    } while (cursor != null);
    assertThat(all).containsExactly(M1.toString(), M3.toString(), M2.toString());
    mvc.perform(get("/api/v1/members/" + id(9999))).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/members/" + id(9999) + "/songs")).andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/songs?size=51",
        "/api/v1/songs?size=0",
        "/api/v1/songs?size=x",
        "/api/v1/songs?sort=WRONG",
        "/api/v1/songs?type=wrong",
        "/api/v1/songs?year=1899",
        "/api/v1/songs?cursor=bad",
        "/api/v1/songs/not-a-uuid",
        "/api/v1/members?status=wrong",
        "/api/v1/members?cursor=bad",
        "/api/v1/songs?memberIds=bad"
      })
  void invalidQueriesReturnProblemWithoutInternalDetails(String path) throws Exception {
    mvc.perform(get(path))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CATALOG_QUERY"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  @Test
  void thousandSongPagesUseConstantRelationshipQueries() throws Exception {
    writer.execute(
        """
        INSERT INTO app.song_entry(id, title, search_title, song_type, work_id, visibility)
        SELECT md5('bench-song-' || n)::uuid, 'Bench Song ' || n, 'bench song ' || n, 'COVER',
          '00000000-0000-0000-0000-00000000000a'::uuid, 'PUBLISHED' FROM generate_series(1, 1000) n
        """);
    writer.execute(
        """
        INSERT INTO app.video(id, song_id, youtube_id, video_kind, availability, source_title, published_at)
        SELECT md5('bench-video-' || n)::uuid, md5('bench-song-' || n)::uuid, lpad((10000 + n)::text, 11, '0'),
          'OFFICIAL_COVER', 'PUBLIC', 'Bench Source ' || n, '2026-03-01T00:00:00Z'::timestamptz
        FROM generate_series(1, 1000) n
        """);
    writer.execute(
        """
        UPDATE app.song_entry s SET representative_video_id = v.id FROM app.video v
          WHERE s.id = v.song_id AND s.search_title LIKE 'bench song %'
        """);
    writer.execute(
        """
        INSERT INTO app.song_member(song_id, member_id, position)
          SELECT id, '00000000-0000-0000-0000-000000000001'::uuid, 0 FROM app.song_entry WHERE search_title LIKE 'bench song %'
        """);
    request("/api/v1/songs?size=20&q=bench");
    SELECTS.set(0);
    assertThat(ids(request("/api/v1/songs?size=20&q=bench"))).hasSize(20);
    int twenty = SELECTS.get();
    SELECTS.set(0);
    assertThat(ids(request("/api/v1/songs?size=50&q=bench"))).hasSize(50);
    assertThat(twenty).isEqualTo(4);
    assertThat(SELECTS.get()).isEqualTo(twenty);
    var durations = new ArrayList<Long>();
    for (int i = 0; i < 20; i++) {
      long started = System.nanoTime();
      request("/api/v1/songs?size=20&q=bench");
      durations.add((System.nanoTime() - started) / 1_000_000);
    }
    durations.sort(Long::compare);
    System.out.println(
        "Catalog local MockMvc sample: 1000 songs, 20 requests, p95=" + durations.get(18) + "ms");
  }

  @Test
  void databaseConstraintsAndCollectorOwnedFieldsAreEnforced() {
    assertThatThrownBy(() -> runtime.execute("UPDATE app.video SET source_title = 'Forbidden'"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                writer.update(
                    "UPDATE app.song_entry SET representative_video_id = ? WHERE id = ?",
                    id(S2.hashCode() + 1000),
                    S1))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> writer.update("INSERT INTO app.song_member VALUES (?, ?, 9, true)", S1, M1))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                writer.update("UPDATE app.song_entry SET is_special_event = true WHERE id = ?", S1))
        .isInstanceOf(DataAccessException.class);
  }
}
