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
import java.util.Map;
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

  @ParameterizedTest
  @ValueSource(strings = {"LATEST", "RELEVANCE", "VIEWS"})
  void whitespaceAndCaseShareMatchingRankingCountsFiltersAndCursorPages(String sort)
      throws Exception {
    writer.update(
        "UPDATE app.song_entry SET title='Milky Way',search_title='milky way' WHERE id=?", S1);
    writer.update("INSERT INTO app.song_alias VALUES (?, '밀키 웨이', '밀키 웨이')", S1);
    writer.update("INSERT INTO app.song_alias VALUES (?, 'MilkyWay', 'milkyway')", S1);
    writer.update(
        "UPDATE app.musical_work SET title='MILKY WAY',search_title='milky way' WHERE id=?", W1);
    // Both title and alias match S1; shared original work also matches S2, without duplicates.
    var first =
        request(
            "/api/v1/songs?q=Milky Way&sort="
                + sort
                + "&size=1&startDate=2026-01-01&endDate=2026-01-02&type=COVER&memberIds="
                + M2);
    assertThat(first.path("totalCount").asLong()).isEqualTo(2);
    var compact =
        request(
            "/api/v1/songs?q=mILkYwAY&sort="
                + sort
                + "&size=1&startDate=2026-01-01&endDate=2026-01-02&type=COVER&memberIds="
                + M2);
    assertThat(ids(first)).isEqualTo(ids(compact));
    var after =
        mapper.readTree(
            mvc.perform(
                    get("/api/v1/songs")
                        .param("q", "m i L k Y w A y")
                        .param("sort", sort)
                        .param("size", "1")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-02")
                        .param("type", "COVER")
                        .param("memberIds", M2.toString())
                        .param("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.path("totalCount").asLong()).isEqualTo(2);
    var all = new ArrayList<>(ids(first));
    all.addAll(ids(after));
    assertThat(all).containsExactlyInAnyOrder(S1.toString(), S2.toString()).doesNotHaveDuplicates();
    assertThat(after.path("hasNext").asBoolean()).isFalse();
    assertThat(ids(request("/api/v1/songs?q=밀키웨이"))).containsExactly(S1.toString());
    assertThat(ids(request("/api/v1/songs?q=밀키　웨이"))).containsExactly(S1.toString());
  }

  @Test
  void whitespaceInsensitiveSearchCoversWorkAliasesParticipantsAndTheirAliases() throws Exception {
    writer.update("INSERT INTO app.work_alias VALUES (?, '점묘의 노래', '점묘의 노래')", W1);
    writer.update("INSERT INTO app.member_alias VALUES (?, '가수 별칭', '가수 별칭')", M1);
    writer.update("INSERT INTO app.artist_alias VALUES (?, '외부 별칭', '외부 별칭')", A1);
    for (var term : List.of("점묘의노래", "점 묘 의 노 래"))
      assertThat(ids(request("/api/v1/songs?q=" + term)))
          .containsExactly(S2.toString(), S1.toString());
    for (var term : List.of("가수Alpha", "가수ALPHA", "가수별칭", "외부Singer", "외부SINGER", "외부별칭")) {
      var path = "/api/v1/songs?q=" + term;
      assertThat(ids(request(path))).contains(S1.toString(), S3.toString()).doesNotHaveDuplicates();
      assertThat(request(path).path("totalCount").asLong()).isEqualTo(ids(request(path)).size());
    }
    assertThat(request("/api/v1/songs?q=  ").path("totalCount").asLong()).isZero();
    mvc.perform(get("/api/v1/songs").param("q", "a" + " ".repeat(200)))
        .andExpect(status().isBadRequest());
  }

  @ParameterizedTest
  @ValueSource(strings = {"LATEST", "RELEVANCE", "VIEWS"})
  void dateRangeUsesRepresentativeVideoKoreanDatesAndCountAcrossPages(String sort)
      throws Exception {
    writer.update(
        "UPDATE app.video SET published_at=?::timestamptz WHERE song_id=?",
        "2025-08-31T14:59:59.999999Z",
        S1);
    writer.update(
        "UPDATE app.video SET published_at=?::timestamptz WHERE song_id=?",
        "2025-08-31T15:00:00Z",
        S2);
    writer.update(
        "UPDATE app.video SET published_at=?::timestamptz WHERE song_id=?",
        "2025-09-30T14:59:59.999999Z",
        S3);
    writer.update(
        "UPDATE app.video SET published_at=?::timestamptz WHERE song_id=?",
        "2025-09-30T15:00:00Z",
        S4);
    // A second upload must not affect the representative video's period or duplicate counts.
    writer.update(
        "INSERT INTO app.video(id,song_id,youtube_id,video_kind,availability,source_title,published_at) VALUES (?,?,'ZZZZZZZZZZZ','AUDIO','PUBLIC','Other upload','2025-09-15T00:00:00Z')",
        id(9999),
        S1);
    var range = "/api/v1/songs?startDate=2025-09-01&endDate=2025-09-30&size=1&sort=" + sort;
    var first = request(range);
    assertThat(ids(first)).containsExactly(S3.toString());
    assertThat(first.path("totalCount").asLong()).isEqualTo(2);
    assertThat(first.path("hasNext").asBoolean()).isTrue();
    var second =
        mapper.readTree(
            mvc.perform(get(range).param("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(ids(second)).containsExactly(S2.toString());
    assertThat(second.path("totalCount").asLong()).isEqualTo(2);
    assertThat(second.path("hasNext").asBoolean()).isFalse();
    for (var changed :
        new String[] {
          "startDate=2025-09-02&endDate=2025-09-30", "startDate=2025-09-01&endDate=2025-09-29", ""
        })
      mvc.perform(
              get("/api/v1/songs?" + changed)
                  .param("sort", sort)
                  .param("cursor", first.path("nextCursor").asText()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_CATALOG_QUERY"));
    assertThat(ids(request("/api/v1/songs?endDate=2025-08-31"))).containsExactly(S1.toString());
    assertThat(ids(request("/api/v1/songs?startDate=2025-10-01"))).containsExactly(S4.toString());
    assertThat(ids(request("/api/v1/songs?startDate=2025-09-01&endDate=2025-09-01")))
        .containsExactly(S2.toString());
    assertThat(request("/api/v1/songs").path("totalCount").asLong()).isEqualTo(4);
    var filtered =
        request(
            "/api/v1/songs?startDate=2025-09-01&endDate=2025-09-30&q=sky&type=COVER&memberIds="
                + M2
                + "&sort=RELEVANCE");
    assertThat(ids(filtered)).containsExactly(S2.toString());
    assertThat(filtered.path("totalCount").asLong()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"2025-02-29", "2025-13-01", "2025-9-01", "2025-09-01T00:00:00", "", "not-a-date"})
  void rejectsInvalidDatesWithFieldErrors(String date) throws Exception {
    for (var field : List.of("startDate", "endDate"))
      mvc.perform(get("/api/v1/songs").param(field, date))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_CATALOG_QUERY"))
          .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
          .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_DATE"))
          .andExpect(jsonPath("$.fieldErrors[0].rejectedValue").doesNotExist());
  }

  @Test
  void validatesDateBoundsConflictsAndLeapDates() throws Exception {
    for (var field : List.of("startDate", "endDate")) {
      for (var date : List.of("1899-12-31", "2101-01-01"))
        mvc.perform(get("/api/v1/songs").param(field, date))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("DATE_OUT_OF_RANGE"));
      mvc.perform(get("/api/v1/songs").param(field, "2025-09-01").param("year", "2025"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.fieldErrors[1].field").value("year"))
          .andExpect(jsonPath("$.fieldErrors[0].code").value("CONFLICTING_FILTER"))
          .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }
    mvc.perform(get("/api/v1/songs?startDate=2025-09-30&endDate=2025-09-01"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_DATE_RANGE"))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("startDate"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("endDate"));
    for (var range :
        List.of(
            "startDate=1900-01-01&endDate=2100-12-31", "startDate=2024-02-29&endDate=2024-02-29"))
      mvc.perform(get("/api/v1/songs?" + range)).andExpect(status().isOk());
  }

  @Test
  void trendingIsDisabledByDefaultWithoutLogin() throws Exception {
    var data = request("/api/v1/songs/trending");
    assertThat(data.path("status").asText()).isEqualTo("DISABLED");
    assertThat(data.path("items").size()).isZero();
  }

  List<String> ids(JsonNode page) {
    var values = new ArrayList<String>();
    page.get("items").forEach(row -> values.add(row.get("id").asText()));
    return values;
  }

  @Test
  void recommendationsAreAnonymousUniquePublicCardsWithGraduatedAndExternalParticipants()
      throws Exception {
    var publication = publish(10L, 0L, null, 20L);
    var pending = UUID.randomUUID();
    writer.update("INSERT INTO app.view_publication VALUES (?, current_timestamp)", pending);
    writer.update(
        "INSERT INTO app.published_video_view VALUES (?, ?, 99999, current_timestamp)",
        pending,
        id(S1.hashCode() + 1000));
    var response = request("/api/v1/songs/recommendations");
    assertThat(ids(response))
        .containsExactlyInAnyOrder(S1.toString(), S2.toString(), S3.toString(), S4.toString());
    for (var card : response.path("items")) {
      var detail = request("/api/v1/songs/" + card.path("id").asText());
      assertThat(card).isEqualTo(detail.get("song"));
    }
    assertThat(response.path("items").toString()).contains("GRADUATED", "EXTERNAL_ARTIST");
    assertThat(
            runtime.queryForObject(
                "SELECT view_publication_id FROM app.catalog_state WHERE singleton", UUID.class))
        .isEqualTo(publication);
    mvc.perform(post("/api/v1/songs/recommendations")).andExpect(status().isForbidden());
  }

  @Test
  void recommendationsExcludeUnavailableDraftUnlistedAndUnconfirmedSongs() throws Exception {
    writer.update("UPDATE app.video SET availability = 'UNAVAILABLE' WHERE song_id = ?", S1);
    writer.update("UPDATE app.song_entry SET visibility = 'DRAFT' WHERE id = ?", S2);
    writer.update("UPDATE app.video SET availability = 'UNLISTED' WHERE song_id = ?", S3);
    writer.update("UPDATE app.video SET published_at = null WHERE song_id = ?", S4);
    assertThat(ids(request("/api/v1/songs/recommendations"))).isEmpty();
    writer.update(
        "UPDATE app.video SET source_published_at = '2026-01-04T00:00:00Z' WHERE song_id = ?", S4);
    assertThat(ids(request("/api/v1/songs/recommendations"))).containsExactly(S4.toString());
  }

  @Test
  void recommendationsReduceMemberRepetitionUsingConfirmedMembersAcrossTheWholeCatalog()
      throws Exception {
    writer.update("UPDATE app.song_entry SET visibility = 'HIDDEN'");
    for (int i = 0; i < 25; i++)
      song(id(300 + i), "Recent " + i, "COVER", null, "2026-03-01T00:00:00Z", M1);
    song(id(400), "Older graduated", "COVER", null, "2025-01-01T00:00:00Z", M2);
    song(id(401), "Older original", "ORIGINAL", null, "2025-01-01T00:00:00Z", M3);
    // Unconfirmed participants must not affect the choice of a different confirmed member.
    writer.update("INSERT INTO app.song_member VALUES (?, ?, 1, false)", id(400), M1);
    var selected = ids(request("/api/v1/songs/recommendations?size=3"));
    assertThat(selected)
        .hasSize(3)
        .doesNotHaveDuplicates()
        .contains(id(400).toString(), id(401).toString());
    assertThat(ids(request("/api/v1/songs/recommendations"))).hasSize(6).doesNotHaveDuplicates();
    assertThat(ids(request("/api/v1/songs/recommendations?size=20")))
        .hasSize(20)
        .doesNotHaveDuplicates();
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
  void totalCountMatchesSearchFiltersAndCountsSongsOnceAcrossRelationshipsAndUploads()
      throws Exception {
    writer.update("INSERT INTO app.song_alias VALUES (?, ?, ?)", S1, "Sky cover", "sky cover");
    writer.update("INSERT INTO app.member_alias VALUES (?, ?, ?)", M1, "Alpha", "alpha");
    writer.update(
        "INSERT INTO app.video(id, song_id, youtube_id, video_kind, availability, source_title, published_at) VALUES (?, ?, '99999999999', 'REUPLOAD', 'PUBLIC', 'Reupload', '2026-02-01T00:00:00Z')",
        id(999),
        S1);
    var cases =
        Map.ofEntries(
            Map.entry("", 4L),
            Map.entry("?q=sky", 2L),
            Map.entry("?q=논브레스 오블리주", 2L),
            Map.entry("?q=외부 가수", 3L),
            Map.entry("?q=알파", 2L),
            Map.entry("?q=%", 1L),
            Map.entry("?q=missing", 0L),
            Map.entry("?q=", 0L),
            Map.entry("?q=+++", 0L),
            Map.entry("?mode=BROWSE", 4L),
            Map.entry("?type=COVER", 3L),
            Map.entry("?memberIds=" + M1 + "," + M2, 4L),
            Map.entry(
                "?q=sky&memberIds="
                    + M1
                    + ","
                    + M2
                    + "&type=COVER&year=2026&multipleParticipants=true",
                1L),
            Map.entry("?memberIds=" + M1 + "&type=ORIGINAL&multipleParticipants=true", 1L),
            Map.entry("?memberIds=" + M3, 0L),
            Map.entry("?multipleParticipants=true", 2L),
            Map.entry("?year=2025", 0L));
    for (var entry : cases.entrySet()) {
      assertThat(request("/api/v1/songs" + entry.getKey()).get("totalCount").asLong())
          .as(entry.getKey())
          .isEqualTo(entry.getValue());
    }
    assertThat(request("/api/v1/members/" + M1 + "/songs?size=1").get("totalCount").asLong())
        .isEqualTo(2);
    assertThat(
            request("/api/v1/members/" + M1 + "/songs?type=COVER&multipleParticipants=true")
                .get("totalCount")
                .asLong())
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"LATEST", "RELEVANCE", "VIEWS"})
  void totalCountIgnoresPageSizeSortAndCursorIncludingTheFinalPage(String sort) throws Exception {
    publish(10L, 10L, 0L, null);
    var first = request("/api/v1/songs?size=2&sort=" + sort);
    assertThat(ids(first)).hasSize(2);
    assertThat(first.get("totalCount").asLong()).isEqualTo(4);
    assertThat(first.get("hasNext").asBoolean()).isTrue();
    var second =
        mapper.readTree(
            mvc.perform(
                    get("/api/v1/songs")
                        .param("size", "2")
                        .param("sort", sort)
                        .param("cursor", first.get("nextCursor").asText()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(ids(second)).hasSize(2).doesNotContainAnyElementsOf(ids(first));
    assertThat(second.get("totalCount").asLong()).isEqualTo(4);
    assertThat(second.get("hasNext").asBoolean()).isFalse();
    assertThat(second.get("nextCursor").isNull()).isTrue();
    assertThat(request("/api/v1/songs?size=50&sort=" + sort).get("totalCount").asLong())
        .isEqualTo(4);
  }

  @Test
  void emptyPageAfterCatalogChangeStillReturnsTheCurrentTotalBeforeTheCursor() throws Exception {
    var first = request("/api/v1/songs?size=2");
    writer.update("UPDATE app.song_entry SET visibility='HIDDEN' WHERE id IN (?, ?)", S1, S2);
    var second =
        mapper.readTree(
            mvc.perform(
                    get("/api/v1/songs")
                        .param("size", "2")
                        .param("cursor", first.get("nextCursor").asText()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(ids(second)).isEmpty();
    assertThat(second.get("totalCount").asLong()).isEqualTo(2);
    assertThat(second.get("hasNext").asBoolean()).isFalse();
    assertThat(second.get("nextCursor").isNull()).isTrue();
  }

  @Test
  void memberOrFiltersAndMultipleParticipantsMatchCounts() throws Exception {
    assertThat(ids(request("/api/v1/songs?memberIds=" + M1 + "," + M2)))
        .hasSize(4)
        .doesNotHaveDuplicates();
    assertThat(
            ids(
                request(
                    "/api/v1/songs?memberIds=" + M1 + "&type=ORIGINAL&multipleParticipants=true")))
        .containsExactly(S3.toString());
    var member = request("/api/v1/members/" + M1).get("member");
    assertThat(member.get("songCounts").get("total").asLong()).isEqualTo(2);
    assertThat(member.get("songCounts").get("originals").asLong()).isEqualTo(1);
    assertThat(member.get("songCounts").get("covers").asLong()).isEqualTo(1);
    assertThat(member.get("songCounts").get("multipleParticipants").asLong()).isEqualTo(2);
    assertThat(ids(request("/api/v1/members/" + M1 + "/songs"))).hasSize(2);
    assertThat(
            ids(request("/api/v1/members/" + M1 + "/songs?type=COVER&multipleParticipants=true")))
        .containsExactly(S1.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"생일 기념", "데뷔 기념", "게임 콜라보 기념"})
  void specialPurposeLabelsAreIndependentOfParticipantCountAndSharedWork(String label)
      throws Exception {
    // S1 and S2 share a work. S1 has multiple participants; S2 has one member.
    writer.update(
        "UPDATE app.song_entry SET is_special_event=true,special_event_label=? WHERE id=?",
        label,
        S2);
    var solo = request("/api/v1/songs/" + S2).get("song");
    assertThat(solo.get("multipleParticipants").asBoolean()).isFalse();
    assertThat(solo.get("isSpecialEvent").asBoolean()).isTrue();
    assertThat(solo.get("specialEventLabel").asText()).isEqualTo(label);
    assertThat(solo.has("collaboration")).isFalse();
    var group = request("/api/v1/songs/" + S1).get("song");
    assertThat(group.get("multipleParticipants").asBoolean()).isTrue();
    assertThat(group.get("isSpecialEvent").asBoolean()).isFalse();
    assertThat(group.get("specialEventLabel").isNull()).isTrue();
    assertThat(group.has("collaboration")).isFalse();
    var filtered = request("/api/v1/songs?multipleParticipants=true");
    assertThat(ids(filtered)).containsExactly(S3.toString(), S1.toString());
    assertThat(filtered.get("totalCount").asLong()).isEqualTo(2);
    var counts = request("/api/v1/members/" + M2).get("member").get("songCounts");
    assertThat(counts.get("multipleParticipants").asLong()).isEqualTo(1);
    assertThat(counts.get("collaborations").asLong())
        .isEqualTo(counts.get("multipleParticipants").asLong());
  }

  @Test
  void onlyConfirmedParticipantsCountAndFilteringKeepsCursorAndTotalsConsistent() throws Exception {
    writer.update("INSERT INTO app.song_member VALUES (?, ?, 1, false)", S2, M3);
    writer.update("INSERT INTO app.song_external_artist VALUES (?, ?, 0, false)", S2, A1);
    assertThat(request("/api/v1/songs/" + S2).get("song").get("multipleParticipants").asBoolean())
        .isFalse();
    var first = request("/api/v1/songs?multipleParticipants=true&size=1");
    assertThat(ids(first)).containsExactly(S3.toString());
    assertThat(first.get("totalCount").asLong()).isEqualTo(2);
    String cursor = first.get("nextCursor").asText();
    var second =
        mapper.readTree(
            mvc.perform(
                    get("/api/v1/songs")
                        .param("multipleParticipants", "true")
                        .param("size", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(ids(second)).containsExactly(S1.toString());
    assertThat(second.get("totalCount").asLong()).isEqualTo(2);
    assertThat(second.get("hasNext").asBoolean()).isFalse();
    mvc.perform(get("/api/v1/songs").param("multipleParticipants", "false").param("cursor", cursor))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CATALOG_QUERY"));
  }

  @Test
  void legacyMemberClientCanLoadDefaultSongsAndReadNumericCounts() throws Exception {
    var page =
        request(
            "/api/v1/members/" + M1 + "/songs?type=ALL&collaboration=false&sort=LATEST&size=20");
    assertThat(ids(page)).containsExactly(S3.toString(), S1.toString());
    assertThat(page.get("totalCount").asLong()).isEqualTo(2);
    for (var card : page.get("items")) assertThat(card.has("collaboration")).isFalse();
    var detail = request("/api/v1/members/" + M1).get("member").get("songCounts");
    assertThat(detail.get("collaborations").isNumber()).isTrue();
    assertThat(detail.get("collaborations").asLong()).isEqualTo(2);
    assertThat(detail.get("collaborations")).isEqualTo(detail.get("multipleParticipants"));
    for (var member : request("/api/v1/members").get("items")) {
      var counts = member.get("songCounts");
      assertThat(counts.get("collaborations").isNumber()).isTrue();
      assertThat(counts.get("collaborations")).isEqualTo(counts.get("multipleParticipants"));
    }
    var empty = request("/api/v1/members/" + M3).get("member").get("songCounts");
    assertThat(empty.get("collaborations").asLong()).isZero();
    assertThat(empty.get("multipleParticipants").asLong()).isZero();
  }

  @Test
  void legacyAndCurrentParticipantNamesShareResultsAndCursorIdentity() throws Exception {
    for (String path : List.of("/api/v1/songs", "/api/v1/members/" + M1 + "/songs")) {
      assertThat(request(path + "?collaboration=false")).isEqualTo(request(path));
      assertThat(request(path + "?collaboration=true"))
          .isEqualTo(request(path + "?multipleParticipants=true"));
      assertThat(request(path + "?collaboration=true&multipleParticipants=true"))
          .isEqualTo(request(path + "?multipleParticipants=true"));
      assertThat(request(path + "?collaboration=false&multipleParticipants=false"))
          .isEqualTo(request(path));
      var first = request(path + "?collaboration=true&size=1");
      var second =
          mapper.readTree(
              mvc.perform(
                      get(path)
                          .param("multipleParticipants", "true")
                          .param("size", "1")
                          .param("cursor", first.get("nextCursor").asText()))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      assertThat(ids(first)).containsExactly(S3.toString());
      assertThat(ids(second)).containsExactly(S1.toString());
      assertThat(second.get("totalCount").asLong()).isEqualTo(2);
      assertThat(second.get("hasNext").asBoolean()).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void conflictingLegacyAndCurrentParticipantFiltersIdentifyBothFields(boolean current)
      throws Exception {
    for (String path : List.of("/api/v1/songs", "/api/v1/members/" + M1 + "/songs")) {
      mvc.perform(
              get(path)
                  .param("collaboration", String.valueOf(!current))
                  .param("multipleParticipants", String.valueOf(current)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_CATALOG_QUERY"))
          .andExpect(jsonPath("$.fieldErrors[0].field").value("collaboration"))
          .andExpect(jsonPath("$.fieldErrors[0].code").value("CONFLICTING_FILTER"))
          .andExpect(jsonPath("$.fieldErrors[1].field").value("multipleParticipants"))
          .andExpect(jsonPath("$.fieldErrors[1].code").value("CONFLICTING_FILTER"));
    }
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
    assertThat(related).isEmpty();
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
  void relatedCoversExcludeMemberOnlyMatchesAndOriginalsBeforeLimiting() throws Exception {
    song(id(201), "Same work cover", "COVER", W1, "2026-01-10T00:00:00Z", M3);
    song(id(202), "Same work multiple participants", "COVER", W1, "2026-01-10T00:00:00Z", M3);
    writer.update("INSERT INTO app.song_member VALUES (?, ?, 1, true)", id(202), M1);
    writer.update("INSERT INTO app.song_member VALUES (?, ?, 2, true)", id(202), M2);
    song(id(203), "Same work original", "ORIGINAL", W1, "2026-01-30T00:00:00Z", M3);
    var otherWork = id(11);
    writer.update(
        "INSERT INTO app.musical_work(id,title,search_title) VALUES (?, 'Other work', 'other work')",
        otherWork);
    for (int i = 210; i < 218; i++)
      song(id(i), "Member only " + i, "COVER", otherWork, "2026-01-31T00:00:00Z", M1);

    var related = request("/api/v1/songs/" + S1).get("relatedSongs");
    var relatedIds = new ArrayList<String>();
    related.forEach(
        card -> {
          relatedIds.add(card.get("id").asText());
          assertThat(card.get("type").asText()).isEqualTo("COVER");
          assertThat(card.get("work").get("id").asText()).isEqualTo(W1.toString());
        });
    assertThat(relatedIds).containsExactly(id(202).toString(), id(201).toString());
  }

  @Test
  void relatedCoversRequireAtLeastOneDifferentConfirmedMember() throws Exception {
    // S1 has M1 and M2; S2 has only M2. Subsets of the current lineup are excluded.
    assertThat(request("/api/v1/songs/" + S1).get("relatedSongs")).isEmpty();
    // The reverse direction includes S1 because its M1 is absent from S2.
    assertThat(request("/api/v1/songs/" + S2).get("relatedSongs").get(0).get("id").asText())
        .isEqualTo(S1.toString());
    writer.update("INSERT INTO app.song_member VALUES (?, ?, 1, false)", S2, M3);
    assertThat(request("/api/v1/songs/" + S1).get("relatedSongs")).isEmpty();
    writer.update("INSERT INTO app.song_member VALUES (?, ?, 2, false)", S1, M3);
    writer.update(
        "UPDATE app.song_member SET confirmed=true WHERE song_id=? AND member_id=?", S2, M3);
    assertThat(request("/api/v1/songs/" + S1).get("relatedSongs").get(0).get("id").asText())
        .isEqualTo(S2.toString());
  }

  @Test
  void relatedCoversAreEmptyWithoutWorkOrEligibleMatches() throws Exception {
    assertThat(request("/api/v1/songs/" + S3).get("relatedSongs")).isEmpty();
    assertThat(request("/api/v1/songs/" + S4).get("relatedSongs")).isEmpty();
    writer.update("UPDATE app.song_entry SET visibility='HIDDEN' WHERE id=?", S1);
    assertThat(request("/api/v1/songs/" + S2).get("relatedSongs")).isEmpty();
  }

  @Test
  void relatedCoversUsePublicEligibilityAndExcludeUnavailableVideos() throws Exception {
    for (String availability : List.of("PRIVATE", "UNLISTED", "DELETED", "UNAVAILABLE")) {
      writer.update("UPDATE app.video SET availability=? WHERE song_id=?", availability, S1);
      assertThat(request("/api/v1/songs/" + S2).get("relatedSongs")).isEmpty();
    }
    writer.update("UPDATE app.video SET availability='PUBLIC' WHERE song_id=?", S1);
    for (String visibility : List.of("DRAFT", "HIDDEN")) {
      writer.update("UPDATE app.song_entry SET visibility=? WHERE id=?", visibility, S1);
      assertThat(request("/api/v1/songs/" + S2).get("relatedSongs")).isEmpty();
    }
    writer.update("UPDATE app.song_entry SET visibility='PUBLISHED' WHERE id=?", S1);
    writer.update(
        "UPDATE app.video SET published_at=null, source_published_at=null WHERE song_id=?", S1);
    assertThat(request("/api/v1/songs/" + S2).get("relatedSongs")).isEmpty();
    writer.update(
        "UPDATE app.video SET source_published_at='2026-01-01T00:00:00Z', embeddable=false WHERE song_id=?",
        S1);
    assertThat(request("/api/v1/songs/" + S2).get("relatedSongs").get(0).get("id").asText())
        .isEqualTo(S1.toString());
    writer.update("UPDATE app.song_entry SET representative_video_id=null WHERE id=?", S1);
    assertThat(request("/api/v1/songs/" + S2).get("relatedSongs")).isEmpty();
  }

  @Test
  void relatedCoversReturnSixLatestUniqueSongsWithDeterministicDateTies() throws Exception {
    for (int i = 201; i <= 207; i++) {
      song(id(i), "Cover " + i, "COVER", W1, "2026-01-10T00:00:00Z", M3);
      writer.update("INSERT INTO app.song_member VALUES (?, ?, 1, true)", id(i), M1);
      writer.update("INSERT INTO app.song_member VALUES (?, ?, 2, true)", id(i), M2);
    }
    // A newer date takes precedence over a larger song ID.
    song(id(200), "Newest cover", "COVER", W1, "2026-01-11T00:00:00Z", M3);
    var related = request("/api/v1/songs/" + S1).get("relatedSongs");
    var relatedIds = new ArrayList<String>();
    related.forEach(card -> relatedIds.add(card.get("id").asText()));
    assertThat(relatedIds)
        .containsExactly(
            id(200).toString(),
            id(207).toString(),
            id(206).toString(),
            id(205).toString(),
            id(204).toString(),
            id(203).toString());
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
      assertThat(page.get("totalCount").asLong()).isEqualTo(all.isEmpty() ? 4 : 5);
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
    assertThat(request("/api/v1/songs?year=2026").get("totalCount").asLong()).isEqualTo(3);
    assertThat(request("/api/v1/songs?year=2025").get("totalCount").asLong()).isEqualTo(1);
  }

  List<Integer> years() throws Exception {
    var result = new ArrayList<Integer>();
    request("/api/v1/songs/years").get("years").forEach(year -> result.add(year.asInt()));
    return result;
  }

  @Test
  void yearOptionsUseRepresentativeDatesKoreanBoundariesAndSourceFallback() throws Exception {
    writer.update(
        "UPDATE app.video SET published_at = '2025-12-31T15:00:00Z' WHERE song_id = ?", S1);
    writer.update(
        "UPDATE app.video SET published_at = '2025-12-31T14:59:59Z' WHERE song_id = ?", S2);
    writer.update(
        "UPDATE app.video SET published_at = null, source_published_at = '2023-12-31T14:59:59Z' WHERE song_id = ?",
        S3);
    writer.update(
        "INSERT INTO app.video(id, song_id, youtube_id, video_kind, availability, source_title, published_at) VALUES (?, ?, '99999999999', 'REUPLOAD', 'PUBLIC', 'Reupload', '2030-01-01T00:00:00Z')",
        id(999),
        S1);
    assertThat(years()).containsExactly(2026, 2025, 2023);
    for (int year : years())
      assertThat(request("/api/v1/songs?year=" + year).get("totalCount").asLong()).isPositive();
  }

  @Test
  void yearOptionsFollowPublicSongEligibilityAndReflectChanges() throws Exception {
    for (int i = 105; i <= 108; i++)
      writer.update(
          "UPDATE app.video SET published_at = ?::timestamptz WHERE song_id = ?",
          (2000 + i - 105) + "-01-01T00:00:00Z",
          id(i));
    writer.update(
        "UPDATE app.video SET published_at = '2018-01-01T00:00:00Z' WHERE song_id = ?", S1);
    assertThat(years()).containsExactly(2026, 2018);
    for (String state : List.of("PRIVATE", "UNLISTED", "DELETED", "UNAVAILABLE")) {
      writer.update("UPDATE app.video SET availability = ? WHERE song_id = ?", state, S1);
      assertThat(years()).containsExactly(2026);
    }
    writer.update("UPDATE app.video SET availability = 'PUBLIC' WHERE song_id = ?", S1);
    for (String state : List.of("HIDDEN", "DRAFT")) {
      writer.update("UPDATE app.song_entry SET visibility = ? WHERE id = ?", state, S1);
      assertThat(years()).containsExactly(2026);
    }
    writer.update("UPDATE app.song_entry SET visibility = 'PUBLISHED' WHERE id = ?", S1);
    writer.update("UPDATE app.song_member SET confirmed = false WHERE song_id = ?", S1);
    assertThat(years()).containsExactly(2026);
    writer.update("UPDATE app.song_member SET confirmed = true WHERE song_id = ?", S1);
    writer.update(
        "UPDATE app.video SET published_at = null, source_published_at = null WHERE song_id = ?",
        S1);
    assertThat(years()).containsExactly(2026);
    writer.update(
        "UPDATE app.video SET published_at = '2018-01-01T00:00:00Z' WHERE song_id = ?", S1);
    assertThat(years()).containsExactly(2026, 2018);
    writer.update("UPDATE app.song_entry SET representative_video_id = null WHERE id = ?", S1);
    assertThat(years()).containsExactly(2026);
  }

  @Test
  void yearOptionsStayWithinSupportedFilterRangeAndCanBeEmpty() throws Exception {
    var songs = List.of(S1, S2, S3, S4);
    var dates = List.of("1899", "1900", "2100", "2101");
    for (int i = 0; i < songs.size(); i++)
      writer.update(
          "UPDATE app.video SET published_at = ?::timestamptz WHERE song_id = ?",
          dates.get(i) + "-01-01T00:00:00Z",
          songs.get(i));
    assertThat(years()).containsExactly(2100, 1900);
    assertThat(request("/api/v1/songs?year=1900").get("totalCount").asLong()).isEqualTo(1);
    assertThat(request("/api/v1/songs?year=2100").get("totalCount").asLong()).isEqualTo(1);
    writer.update("UPDATE app.song_entry SET visibility = 'HIDDEN'");
    assertThat(years()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void memberOrderAndCursorUseGenerationDebutNameAndIdWithGraduatesLast(int size) throws Exception {
    writer.execute("TRUNCATE app.member CASCADE");
    String[] names = {"하", "가", "다", "나", "라", "마", "바", "사", "아", "다"};
    Integer[] generations = {1, 1, 2, 2, 2, 4, null, null, 1, 2};
    String[] dates = {
      "2022-01-08",
      "2025-09-20",
      "2023-06-10",
      "2023-06-10",
      null,
      "2026-10-17",
      "2020-01-01",
      null,
      "2001-01-01",
      "2023-06-10"
    };
    for (int n = 0; n < names.length; n++)
      writer.update(
          "INSERT INTO app.member(id,name,search_name,generation,debut_date,activity_status) VALUES(?,?,?,?,CAST(? AS date),?)",
          id(901 + n),
          names[n],
          names[n],
          generations[n],
          dates[n],
          n == 8 ? "GRADUATED" : "ACTIVE");
    writer.update("UPDATE app.member SET display_order=2 WHERE id=?", id(901));
    writer.update("UPDATE app.member SET display_order=1 WHERE id=?", id(902));
    writer.update("UPDATE app.member SET display_order=2 WHERE id=?", id(904));
    writer.update("UPDATE app.member SET display_order=1 WHERE id=?", id(903));
    var order = List.of(902, 901, 903, 904, 910, 905, 906, 907, 908, 909);
    for (String filter : List.of("ALL", "ACTIVE", "GRADUATED")) {
      var expected =
          order.stream()
              .filter(n -> filter.equals("ALL") || (filter.equals("GRADUATED") == (n == 909)))
              .map(n -> id(n).toString())
              .toList();
      var all = new ArrayList<String>();
      String cursor = null;
      do {
        var req =
            get("/api/v1/members").param("size", String.valueOf(size)).param("status", filter);
        if (cursor != null) req.param("cursor", cursor);
        var page =
            mapper.readTree(
                mvc.perform(req)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
        all.addAll(ids(page));
        cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        assertThat(all.size()).isLessThanOrEqualTo(expected.size());
      } while (cursor != null);
      assertThat(all).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
      assertThat(ids(request("/api/v1/members?size=50&status=" + filter)))
          .containsExactlyElementsOf(expected);
    }
  }

  @Test
  void oldMemberOrderCursorRequiresRestartingTheList() throws Exception {
    String old =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mapper.writeValueAsBytes(
                    java.util.Map.of(
                        "version",
                        1,
                        "filter",
                        "ALL",
                        "activityOrder",
                        1,
                        "searchName",
                        "member",
                        "id",
                        M1)));
    mvc.perform(get("/api/v1/members").param("cursor", old))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CATALOG_QUERY"));
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
        "/api/v1/songs?memberIds=bad",
        "/api/v1/songs/recommendations?size=0",
        "/api/v1/songs/recommendations?size=-1",
        "/api/v1/songs/recommendations?size=21",
        "/api/v1/songs/recommendations?size=x"
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
    var twentyPage = request("/api/v1/songs?size=20&q=bench");
    assertThat(ids(twentyPage)).hasSize(20);
    assertThat(twentyPage.get("totalCount").asLong()).isEqualTo(1000);
    int twenty = SELECTS.get();
    SELECTS.set(0);
    assertThat(ids(request("/api/v1/songs?size=50&q=bench"))).hasSize(50);
    assertThat(twenty).isEqualTo(5);
    assertThat(SELECTS.get()).isEqualTo(twenty);
    SELECTS.set(0);
    assertThat(ids(request("/api/v1/songs/recommendations?size=6")))
        .hasSize(6)
        .doesNotHaveDuplicates();
    int sixRecommendations = SELECTS.get();
    SELECTS.set(0);
    assertThat(ids(request("/api/v1/songs/recommendations?size=20")))
        .hasSize(20)
        .doesNotHaveDuplicates();
    assertThat(sixRecommendations).isEqualTo(5);
    assertThat(SELECTS.get()).isEqualTo(sixRecommendations);
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
