package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Tag("integration")
class SchemaSquashIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine")
          .withInitScript("db/create-test-roles.sql")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("tools").toAbsolutePath()), "/opt/rebaseline");

  @BeforeAll
  static void python() throws Exception {
    var result = postgres.execInContainer("apk", "add", "--no-cache", "python3");
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }

  String url(String database) {
    return "jdbc:postgresql://"
        + postgres.getHost()
        + ":"
        + postgres.getMappedPort(5432)
        + "/"
        + database;
  }

  JdbcTemplate owner(String database) {
    return new JdbcTemplate(
        new DriverManagerDataSource(url(database), "stelody_migrator", "test-migrator"));
  }

  org.flywaydb.core.api.configuration.FluentConfiguration flyway(String database, boolean legacy) {
    var config =
        Flyway.configure()
            .dataSource(url(database), "stelody_migrator", "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    return legacy ? config.locations(LegacyMigrations.location()) : config;
  }

  void create(String database, boolean legacy) {
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    admin.execute("CREATE DATABASE " + database + " OWNER stelody_migrator");
    flyway(database, legacy).load().migrate();
  }

  String schema(String database) throws Exception {
    var dump =
        postgres.execInContainer(
            "pg_dump",
            "-U",
            "stelody_migrator",
            "-d",
            database,
            "--schema-only",
            "--schema=app",
            "--schema=session",
            "--no-owner",
            "--no-comments",
            "--exclude-table=app.flyway_schema_history");
    assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();
    return dump.getStdout().replaceAll("(?m)^\\\\(?:un)?restrict .*\\R", "");
  }

  Map<String, List<String>> data(String database) {
    var db = owner(database);
    var result = new TreeMap<String, List<String>>();
    db.queryForList(
            "SELECT schemaname,tablename FROM pg_tables WHERE schemaname IN ('app','session') AND tablename<>'flyway_schema_history' ORDER BY 1,2")
        .forEach(
            t -> {
              String table = t.get("schemaname") + "." + t.get("tablename");
              result.put(
                  table,
                  db.queryForList(
                      "SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY to_jsonb(t)::text",
                      String.class));
            });
    return result;
  }

  org.testcontainers.containers.Container.ExecResult command(String database, String... arguments)
      throws Exception {
    var args =
        new ArrayList<>(
            List.of(
                "env",
                "PGHOST=127.0.0.1",
                "PGDATABASE=" + database,
                "PGUSER=stelody_migrator",
                "PGPASSWORD=test-migrator",
                "python3",
                "/opt/rebaseline/rebaseline.py",
                "--database=" + database,
                "--runtime-role=stelody_app"));
    args.addAll(List.of(arguments));
    return postgres.execInContainer(args.toArray(String[]::new));
  }

  void apply(String database) throws Exception {
    var result =
        command(
            database,
            "--apply",
            "--services-stopped",
            "--backup-reference=isolated-test-backup",
            "--history-backup=/tmp/" + database + "-history.json");
    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
  }

  @Test
  void initialSchemaAndPrivilegesMatchTheEntireLegacyChainAndSeedValues() throws Exception {
    create("legacy_schema", true);
    create("squashed_schema", false);
    assertThat(schema("squashed_schema")).isEqualTo(schema("legacy_schema"));
    var mapper = new ObjectMapper();
    var old = data("legacy_schema");
    var fresh = data("squashed_schema");
    for (String table : old.keySet()) {
      // Initial control/profile timestamps reflect their creation transaction.
      var before =
          old.get(table).stream()
              .map(
                  v -> {
                    var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(v);
                    tree.remove("edited_at");
                    return tree.toString();
                  })
              .sorted()
              .toList();
      var after =
          fresh.get(table).stream()
              .map(
                  v -> {
                    var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(v);
                    tree.remove("edited_at");
                    return tree.toString();
                  })
              .sorted()
              .toList();
      assertThat(after).as(table).isEqualTo(before);
    }
    assertThat(flyway("squashed_schema", false).load().info().current().getVersion().getVersion())
        .isEqualTo("1");
    assertThat(flyway("squashed_schema", false).load().migrate().migrationsExecuted).isZero();
  }

  @Test
  void initialSchemaSupportsHostedExtensionNamespaceCustomRuntimeAndAbsentCollector() {
    String database = "hosted_extension";
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    admin.execute("CREATE ROLE squash_custom_runtime");
    admin.execute("CREATE DATABASE " + database + " OWNER stelody_migrator");
    var databaseAdmin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                url(database), postgres.getUsername(), postgres.getPassword()));
    databaseAdmin.execute("CREATE EXTENSION pg_trgm WITH SCHEMA public");
    admin.execute("ALTER ROLE stelody_collector RENAME TO squash_hidden_collector");
    try {
      flyway(database, false)
          .placeholders(Map.of("runtimeRole", "squash_custom_runtime"))
          .load()
          .migrate();
      var db = owner(database);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_opclass op ON op.oid=ANY(i.indclass::oid[]) JOIN pg_namespace ext ON ext.oid=op.opcnamespace WHERE n.nspname='app' AND ext.nspname='public' AND op.opcname='gin_trgm_ops'",
                  Integer.class))
          .isEqualTo(16);
      assertThat(
              db.queryForObject(
                  "SELECT has_table_privilege('squash_custom_runtime','session.spring_session','SELECT,INSERT,UPDATE,DELETE')",
                  Boolean.class))
          .isTrue();
      assertThat(
              db.queryForObject(
                  "SELECT has_column_privilege('squash_custom_runtime','app.app_user','role','UPDATE')",
                  Boolean.class))
          .isFalse();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM pg_roles WHERE rolname='stelody_collector'", Integer.class))
          .isZero();
    } finally {
      admin.execute("ALTER ROLE squash_hidden_collector RENAME TO stelody_collector");
    }
  }

  @Test
  void rebaselinePreservesAllRowsAndSchemaAndCanBeRolledBackBeforeTheNextMigration()
      throws Exception {
    String database = "history_transition";
    create(database, true);
    var db = owner(database);
    db.execute(
        """
      INSERT INTO app.app_user(id,google_subject,email) VALUES('00000000-0000-0000-0000-000000000001','fixture','fixture@example.invalid');
      INSERT INTO app.song_entry(id,title,search_title,song_type) VALUES('00000000-0000-0000-0000-000000000002','fixture','fixture','COVER');
      INSERT INTO app.favorite(user_id,song_id) VALUES('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002');
      INSERT INTO app.playlist(id,user_id,name,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','keep',now(),now());
      INSERT INTO app.playlist_item(id,playlist_id,song_id,position,added_at) VALUES(gen_random_uuid(),'00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002',0,now());
      INSERT INTO session.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) VALUES('primary','session',1,1,1800,9999999999999,'fixture');
      INSERT INTO app.persistent_login(id,user_id,token_hash,csrf_token,created_at,expires_at) VALUES(gen_random_uuid(),'00000000-0000-0000-0000-000000000001',repeat('a',64),'csrf',now(),now()+interval '30 days');
      INSERT INTO app.youtube_connection(user_id,generation,status,access_token,refresh_token,expires_at) VALUES('00000000-0000-0000-0000-000000000001',gen_random_uuid(),'CONNECTED','encrypted-access','encrypted-refresh',now()+interval '1 hour');
      """);
    var rows = data(database);
    var ddl = schema(database);
    var history =
        db.queryForList("SELECT * FROM app.flyway_schema_history ORDER BY installed_rank");
    assertThat(command(database).getStdout()).contains("READY_TO_REBASELINE");
    assertThat(db.queryForList("SELECT * FROM app.flyway_schema_history ORDER BY installed_rank"))
        .isEqualTo(history);
    apply(database);
    assertThat(data(database)).isEqualTo(rows);
    assertThat(schema(database)).isEqualTo(ddl);
    flyway(database, false).load().validate();
    assertThat(flyway(database, false).load().migrate().migrationsExecuted).isZero();
    assertThat(command(database, "--apply").getStdout()).contains("ALREADY_REBASELINED");
    var rollback =
        command(
            database,
            "--apply",
            "--services-stopped",
            "--backup-reference=isolated-test-backup",
            "--restore-history=/tmp/" + database + "-history.json");
    assertThat(rollback.getExitCode()).as(rollback.getStderr()).isZero();
    flyway(database, true).load().validate();
    assertThat(db.queryForList("SELECT * FROM app.flyway_schema_history ORDER BY installed_rank"))
        .isEqualTo(history);
    assertThat(data(database)).isEqualTo(rows);
  }

  @Test
  void nextV2AppliesOnceAndPreventsRollingHistoryBackAcrossLaterChanges() throws Exception {
    String database = "next_v2";
    create(database, true);
    apply(database);
    Path directory = Files.createTempDirectory("stelody-next-migration-");
    Path script = directory.resolve("V2__followup_probe.sql");
    try {
      Files.writeString(script, "CREATE TABLE app.followup_probe(id integer PRIMARY KEY);");
      var config =
          flyway(database, false).locations("classpath:db/migration", "filesystem:" + directory);
      assertThat(config.load().migrate().migrationsExecuted).isEqualTo(1);
      assertThat(config.load().migrate().migrationsExecuted).isZero();
      assertThat(
              command(
                      database,
                      "--apply",
                      "--services-stopped",
                      "--backup-reference=test",
                      "--restore-history=/tmp/" + database + "-history.json")
                  .getExitCode())
          .isNotZero();
      assertThat(config.load().info().current().getVersion().getVersion()).isEqualTo("2");
    } finally {
      Files.deleteIfExists(script);
      Files.deleteIfExists(directory);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "checksum",
        "incomplete",
        "failed",
        "active",
        "wrong_database",
        "transaction_failure"
      })
  void refusesUnsafeTransitionsWithoutChangingHistory(String reason) throws Exception {
    String database = "refuse_" + reason;
    create(database, true);
    var db = owner(database);
    if (reason.equals("checksum"))
      db.execute("UPDATE app.flyway_schema_history SET checksum=0 WHERE version='35'");
    if (reason.equals("incomplete"))
      db.execute("DELETE FROM app.flyway_schema_history WHERE version='35'");
    if (reason.equals("failed"))
      db.execute("UPDATE app.flyway_schema_history SET success=false WHERE version='35'");
    if (reason.equals("transaction_failure"))
      db.execute(
          """
          CREATE FUNCTION app.reject_test_baseline() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN IF NEW.type='BASELINE' THEN RAISE EXCEPTION 'fixture failure after history deletion'; END IF; RETURN NEW; END $$;
          CREATE TRIGGER reject_test_baseline BEFORE INSERT ON app.flyway_schema_history
          FOR EACH ROW EXECUTE FUNCTION app.reject_test_baseline();
          """);
    var history =
        db.queryForList("SELECT * FROM app.flyway_schema_history ORDER BY installed_rank");
    var rows = data(database);
    try (var active =
        reason.equals("active")
            ? DriverManager.getConnection(url(database), "stelody_app", "test-runtime")
            : null) {
      var result =
          command(
              database,
              "--apply",
              "--services-stopped",
              "--backup-reference=test",
              "--history-backup=/tmp/" + database + ".json",
              "--database=" + (reason.equals("wrong_database") ? "different_database" : database));
      assertThat(result.getExitCode()).isNotZero();
    }
    assertThat(db.queryForList("SELECT * FROM app.flyway_schema_history ORDER BY installed_rank"))
        .isEqualTo(history);
    assertThat(data(database)).isEqualTo(rows);
  }
}
