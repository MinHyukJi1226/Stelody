package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.*;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class CompactSearchIndexIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @ParameterizedTest
  @ValueSource(strings = {"public", "app"})
  void migrationIndexesTheExactSearchExpressionsInEitherExtensionSchema(String extensionSchema)
      throws Exception {
    String database = "compact_search_" + extensionSchema;
    try (var admin =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      admin.createStatement().execute("CREATE DATABASE " + database + " OWNER stelody_migrator");
    }
    String url =
        "jdbc:postgresql://"
            + postgres.getHost()
            + ":"
            + postgres.getMappedPort(5432)
            + "/"
            + database;
    var cfg =
        Flyway.configure()
            .dataSource(url, "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("4").load().migrate();
    try (var c = DriverManager.getConnection(url, "stelody_migrator", "test-migrator")) {
      c.createStatement().execute("CREATE EXTENSION pg_trgm WITH SCHEMA " + extensionSchema);
      cfg.target("22").load().migrate();
      var st = c.createStatement();
      for (var definition :
          Map.of(
                  "song_entry",
                  "title,search_title,song_type",
                  "musical_work",
                  "title,search_title",
                  "member",
                  "name,search_name,activity_status",
                  "artist",
                  "name,search_name")
              .entrySet()) {
        String table = definition.getKey();
        st.execute(
            "INSERT INTO app."
                + table
                + "(id,"
                + definition.getValue()
                + ") SELECT gen_random_uuid(),'Display '||n,CASE WHEN n=2500 THEN 'search needle' ELSE 'unrelated '||n END"
                + (table.equals("song_entry")
                    ? ",'COVER'"
                    : table.equals("member") ? ",'ACTIVE'" : "")
                + " FROM generate_series(1,5000)n");
      }
      for (var stem : new String[] {"song", "work", "member", "artist"}) {
        String parent =
            switch (stem) {
              case "song" -> "song_entry";
              case "work" -> "musical_work";
              default -> stem;
            };
        st.execute(
            "INSERT INTO app."
                + stem
                + "_alias("
                + stem
                + "_id,alias,search_alias) SELECT id,'Alias '||row_number() OVER(),CASE WHEN row_number() OVER()=2500 THEN 'search needle' ELSE 'unrelated alias '||row_number() OVER() END FROM app."
                + parent);
      }
      for (var entry :
          Map.of(
                  "song_entry",
                  "search_title",
                  "song_alias",
                  "search_alias",
                  "musical_work",
                  "search_title",
                  "work_alias",
                  "search_alias",
                  "member",
                  "search_name",
                  "member_alias",
                  "search_alias",
                  "artist",
                  "search_name",
                  "artist_alias",
                  "search_alias")
              .entrySet()) {
        st.execute("ANALYZE app." + entry.getKey());
        var before = new StringBuilder();
        try (var r =
            st.executeQuery(
                "EXPLAIN (COSTS OFF) SELECT * FROM app."
                    + entry.getKey()
                    + " WHERE replace("
                    + entry.getValue()
                    + ",' ','') LIKE '%searchneedle%'")) {
          while (r.next()) before.append(r.getString(1)).append('\n');
        }
        assertThat(before.toString()).as(entry.getKey() + " before V23").contains("Seq Scan");
      }
      assertThat(cfg.target("23").load().migrate().migrationsExecuted).isEqualTo(1);
      for (var entry :
          Map.of(
                  "song_entry",
                  "search_title",
                  "song_alias",
                  "search_alias",
                  "musical_work",
                  "search_title",
                  "work_alias",
                  "search_alias",
                  "member",
                  "search_name",
                  "member_alias",
                  "search_alias",
                  "artist",
                  "search_name",
                  "artist_alias",
                  "search_alias")
              .entrySet()) {
        String table = entry.getKey(), column = entry.getValue();
        st.execute("ANALYZE app." + table);
        // Use the normal planner: the test never disables sequential scans.
        var plan = new StringBuilder();
        try (var r =
            st.executeQuery(
                "EXPLAIN (COSTS OFF) SELECT * FROM app."
                    + table
                    + " WHERE replace("
                    + column
                    + ",' ','') LIKE '%searchneedle%'")) {
          while (r.next()) plan.append(r.getString(1)).append('\n');
        }
        assertThat(plan.toString())
            .as(table + " compact substring lookup")
            .contains(table + "_compact_trgm_idx");
      }
      var plan = new StringBuilder();
      try (var r =
          st.executeQuery(
              "EXPLAIN (COSTS OFF) SELECT * FROM app.song_entry WHERE replace(search_title,' ','')='searchneedle'")) {
        while (r.next()) plan.append(r.getString(1)).append('\n');
      }
      assertThat(plan.toString()).contains("song_search_compact_title_idx");
    }
  }
}
