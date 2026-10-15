package com.stelody;

import static org.assertj.core.api.Assertions.*;

import com.stelody.catalog.domain.SearchText;
import java.nio.file.*;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Testcontainers
@Tag("integration")
class SongAliasBatchIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  static final ObjectMapper mapper = new ObjectMapper();
  static JsonNode fixture;
  static String sql;

  static Connection connect() throws Exception {
    return DriverManager.getConnection(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
  }

  @BeforeAll
  static void migrate() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator")
        .schemas("app", "session")
        .defaultSchema("app")
        .placeholders(Map.of("runtimeRole", "stelody_app"))
        .load()
        .migrate();
    fixture =
        mapper.readTree(
            Files.readString(Path.of("src/test/resources/db/song-search-aliases-20261016.json")));
    sql = Files.readString(Path.of("../infra/catalog/song-search-aliases-20261016.sql"));
  }

  @BeforeEach
  void seedReviewedCatalog() throws Exception {
    try (var c = connect()) {
      c.createStatement().execute("TRUNCATE app.song_entry CASCADE; DELETE FROM app.catalog_audit");
      for (var item : fixture) {
        try (var p =
            c.prepareStatement(
                "INSERT INTO app.song_entry(id,title,search_title,song_type,version) VALUES(?,?,?,'COVER',?)")) {
          p.setObject(1, UUID.fromString(item.path("songId").asText()));
          p.setString(2, item.path("title").asText());
          p.setString(3, SearchText.normalize(item.path("title").asText()));
          p.setLong(4, item.path("expectedVersion").asLong());
          p.executeUpdate();
        }
        for (var alias : item.path("expectedAliases")) {
          try (var p = c.prepareStatement("INSERT INTO app.song_alias VALUES(?,?,?)")) {
            p.setObject(1, UUID.fromString(item.path("songId").asText()));
            p.setString(2, alias.asText());
            p.setString(3, SearchText.normalize(alias.asText()));
            p.executeUpdate();
          }
        }
      }
    }
  }

  int count(Connection c, String table) throws Exception {
    try (var r = c.createStatement().executeQuery("SELECT count(*) FROM app." + table)) {
      r.next();
      return r.getInt(1);
    }
  }

  @Test
  void onlyJapaneseTitlesChangeEnglishTitlesStayAndAliasesAreAuditedAndIdempotent()
      throws Exception {
    try (var c = connect()) {
      int before = count(c, "song_alias");
      c.createStatement().execute(sql);
      assertThat(count(c, "song_alias")).isEqualTo(before + 189);
      assertThat(count(c, "catalog_audit")).isEqualTo(157);
      int renamed = 0;
      for (var item : fixture) {
        String oldTitle = item.path("title").asText(), newTitle = item.path("newTitle").asText();
        if (!oldTitle.equals(newTitle)) {
          renamed++;
          assertThat(oldTitle).matches(".*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}].*");
          assertThat(oldTitle).doesNotContainPattern("[A-Za-z]");
          assertThat(item.path("addAliases").valueStream().map(JsonNode::asText).toList())
              .contains(oldTitle);
        }
        if (SearchText.normalize(oldTitle).matches(".*[a-z].*"))
          assertThat(newTitle).isEqualTo(oldTitle);
        try (var p =
            c.prepareStatement(
                "SELECT title,search_title,version FROM app.song_entry WHERE id=?")) {
          p.setObject(1, UUID.fromString(item.path("songId").asText()));
          var r = p.executeQuery();
          r.next();
          assertThat(r.getString(1)).isEqualTo(newTitle);
          assertThat(r.getString(2)).isEqualTo(SearchText.normalize(newTitle));
          assertThat(r.getLong(3)).isEqualTo(item.path("expectedVersion").asLong() + 1);
        }
        for (var name : new String[] {"expectedAliases", "addAliases"})
          for (var a : item.path(name)) {
            try (var p =
                c.prepareStatement(
                    "SELECT alias FROM app.song_alias WHERE song_id=? AND search_alias=?")) {
              p.setObject(1, UUID.fromString(item.path("songId").asText()));
              p.setString(2, SearchText.normalize(a.asText()));
              var r = p.executeQuery();
              assertThat(r.next()).isTrue();
              assertThat(r.getString(1)).isEqualTo(a.asText());
            }
          }
      }
      assertThat(renamed).isEqualTo(14);
      c.createStatement().execute(sql);
      assertThat(count(c, "song_alias")).isEqualTo(before + 189);
      assertThat(count(c, "catalog_audit")).isEqualTo(157);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "title", "missing"})
  void anyConflictCancelsAllTitleAndAliasChanges(String conflict) throws Exception {
    try (var c = connect()) {
      var id = UUID.fromString(fixture.get(fixture.size() - 1).path("songId").asText());
      if (conflict.equals("missing")) {
        try (var remove = c.prepareStatement("DELETE FROM app.song_alias WHERE song_id=?")) {
          remove.setObject(1, id);
          remove.executeUpdate();
        }
      }
      try (var p =
          c.prepareStatement(
              switch (conflict) {
                case "version" -> "UPDATE app.song_entry SET version=version+1 WHERE id=?";
                case "title" -> "UPDATE app.song_entry SET title='Changed' WHERE id=?";
                default -> "DELETE FROM app.song_entry WHERE id=?";
              })) {
        p.setObject(1, id);
        p.executeUpdate();
      }
      int before = count(c, "song_alias");
      assertThatThrownBy(() -> c.createStatement().execute(sql))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("Catalog batch conflict");
      c.createStatement().execute("ROLLBACK");
      assertThat(count(c, "song_alias")).isEqualTo(before);
      assertThat(count(c, "catalog_audit")).isZero();
    }
  }
}
