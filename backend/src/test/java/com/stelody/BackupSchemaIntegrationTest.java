package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Tag("integration")
class BackupSchemaIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine")
          .withInitScript("db/create-test-roles.sql")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("../infra/backup").toAbsolutePath().normalize()),
              "/opt/stelody-backup");

  @BeforeAll
  static void installPython() throws Exception {
    var installed = postgres.execInContainer("apk", "add", "--no-cache", "python3");
    assertThat(installed.getExitCode()).as(installed.getStderr()).isZero();
  }

  JdbcTemplate jdbc(String database, String user, String password) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + postgres.getHost()
                + ":"
                + postgres.getMappedPort(5432)
                + "/"
                + database,
            user,
            password));
  }

  @ParameterizedTest
  @ValueSource(ints = {19, 20, 21, 22, 23, 24})
  void realBackupAndIsolatedRestorePreserveProfilesAndPrivateListsAndReplayWithdrawals(int version)
      throws Exception {
    String source = "backup_source_v" + version, target = "backup_restore_v" + version;
    var admin = jdbc(postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword());
    admin.execute("CREATE DATABASE " + source + " OWNER stelody_migrator");
    admin.execute("CREATE DATABASE " + target + " OWNER stelody_migrator");
    var owner = jdbc(source, "stelody_migrator", "test-migrator");
    var cfg =
        Flyway.configure()
            .dataSource(
                "jdbc:postgresql://"
                    + postgres.getHost()
                    + ":"
                    + postgres.getMappedPort(5432)
                    + "/"
                    + source,
                "stelody_migrator",
                "test-migrator")
            .schemas("app", "session")
            .defaultSchema("app")
            .placeholders(Map.of("runtimeRole", "stelody_app"));
    cfg.target("19").load().migrate();
    owner.execute(
        "INSERT INTO app.member(id,name,search_name,generation,activity_status,profile_image_url,birthday_month,birthday_day) VALUES('87a9538a-dade-571b-b249-ce12099fd04b','시라유키 히나','시라유키 히나',2,'ACTIVE','https://example.invalid/hina.png',1,5)");
    if (version > 19) cfg.target(String.valueOf(version)).load().migrate();
    owner.execute(
        "INSERT INTO app.app_user(id,google_subject,email) VALUES('00000000-0000-0000-0000-000000000001','keep','keep@example.invalid'),('00000000-0000-0000-0000-000000000002','withdraw','withdraw@example.invalid')");
    owner.execute(
        "INSERT INTO app.song_entry(id,title,search_title,song_type) VALUES('00000000-0000-0000-0000-000000000003','fixture song','fixture song','COVER')");
    owner.execute(
        "INSERT INTO app.song_member(song_id,member_id,position) VALUES('00000000-0000-0000-0000-000000000003','87a9538a-dade-571b-b249-ce12099fd04b',0)");
    owner.execute(
        "INSERT INTO app.favorite(user_id,song_id) SELECT id,'00000000-0000-0000-0000-000000000003'::uuid FROM app.app_user");
    owner.execute(
        "INSERT INTO app.playlist(id,user_id,name,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000001','keep list',now(),now()),('00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000002','withdraw list',now(),now())");
    owner.execute(
        "INSERT INTO app.playlist_item(id,playlist_id,song_id,position,added_at) VALUES('00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003',0,now())");
    owner.execute(
        "INSERT INTO session.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) VALUES('fixture-primary','fixture-session',1,1,1800,1800001,'keep')");
    owner.execute(
        "INSERT INTO app.youtube_connection(user_id,generation,status,access_token,refresh_token,expires_at) VALUES('00000000-0000-0000-0000-000000000001',gen_random_uuid(),'CONNECTED','test-access','test-refresh',now()+interval '1 hour')");
    if (version >= 24)
      owner.execute(
          "INSERT INTO app.youtube_authorization(user_id,generation,state_hash,session_hash,verifier,nonce,expires_at,return_path,callback_client,callback_uri) VALUES('00000000-0000-0000-0000-000000000001',gen_random_uuid(),'state','session','encrypted-verifier','nonce',now()+interval '5 minutes','/library/youtube','local','http://localhost:5173/api/v1/me/youtube/callback/local')");
    var profiles = owner.queryForList("SELECT * FROM app.member ORDER BY id");
    var channels = owner.queryForList("SELECT * FROM app.channel ORDER BY id");
    String directory = "/tmp/backups-v" + version;
    var backup =
        postgres.execInContainer(
            "env",
            "PGHOST=127.0.0.1",
            "PGPORT=5432",
            "PGDATABASE=" + source,
            "PGUSER=stelody_migrator",
            "PGPASSWORD=test-migrator",
            "python3",
            "/opt/stelody-backup/database.py",
            "backup",
            "--directory",
            directory);
    assertThat(backup.getExitCode()).as(backup.getStdout() + backup.getStderr()).isZero();
    var mapper = new ObjectMapper();
    String bundle = mapper.readTree(backup.getStdout()).path("backup").asText();
    var manifest = postgres.execInContainer("cat", bundle + "/manifest.json");
    assertThat(mapper.readTree(manifest.getStdout()).path("schemaVersion").asInt())
        .isEqualTo(version);
    String ledger = "/tmp/withdrawals-v" + version + ".json";
    var reconcile =
        postgres.execInContainer(
            "python3",
            "-c",
            "import json,os,datetime; os.umask(0o077); t=datetime.datetime.now(datetime.timezone.utc).isoformat(); "
                + "open('"
                + ledger
                + "','w').write(json.dumps({'complete':True,'servicesStoppedAt':t,'verifiedThrough':t,'withdrawnUserIds':['00000000-0000-0000-0000-000000000002']}))");
    assertThat(reconcile.getExitCode()).as(reconcile.getStderr()).isZero();
    var restored =
        postgres.execInContainer(
            "env",
            "PGHOST=127.0.0.1",
            "PGPORT=5432",
            "PGDATABASE=" + target,
            "PGUSER=stelody_migrator",
            "PGPASSWORD=test-migrator",
            "python3",
            "/opt/stelody-backup/database.py",
            "restore",
            "--backup",
            bundle,
            "--target",
            target,
            "--withdrawals",
            ledger);
    assertThat(restored.getExitCode()).as(restored.getStdout() + restored.getStderr()).isZero();
    var result = mapper.readTree(restored.getStdout());
    assertThat(result.path("withdrawalsReconciled").asInt()).isEqualTo(1);
    assertThat(result.path("readyForPublicTraffic").asBoolean()).isFalse();
    var runtime = jdbc(target, "stelody_app", "test-runtime");
    if (version >= 24) {
      assertThat(
              runtime.queryForObject(
                  "SELECT count(*) FROM information_schema.columns WHERE table_schema='app' AND table_name='youtube_authorization' AND column_name IN ('return_path','callback_client','callback_uri')",
                  Integer.class))
          .isEqualTo(3);
      assertThat(
              runtime.queryForObject(
                  "SELECT count(*) FROM app.youtube_authorization", Integer.class))
          .isZero();
      assertThat(
              runtime.queryForObject(
                  "SELECT has_table_privilege(current_user,'app.youtube_authorization','INSERT,UPDATE,DELETE')",
                  Boolean.class))
          .isTrue();
    }
    if (version >= 23)
      assertThat(
              runtime.queryForObject(
                  "SELECT count(*) FROM pg_indexes WHERE schemaname='app' AND (indexname LIKE '%_compact_trgm_idx' OR indexname='song_search_compact_title_idx')",
                  Integer.class))
          .isEqualTo(9);

    assertThat(runtime.queryForList("SELECT * FROM app.member ORDER BY id")).isEqualTo(profiles);
    assertThat(runtime.queryForList("SELECT * FROM app.channel ORDER BY id")).isEqualTo(channels);
    for (String table :
        new String[] {
          "app.app_user", "app.favorite", "app.playlist", "app.playlist_item", "app.song_member"
        })
      assertThat(runtime.queryForObject("SELECT count(*) FROM " + table, Integer.class))
          .as(table)
          .isEqualTo(1);
    for (String table : new String[] {"session.spring_session", "app.youtube_connection"})
      assertThat(runtime.queryForObject("SELECT count(*) FROM " + table, Integer.class))
          .as(table)
          .isZero();
    assertThat(
            runtime.queryForObject(
                "SELECT has_table_privilege(current_user,'app.member','SELECT')", Boolean.class))
        .isTrue();
    assertThat(
            runtime.queryForObject(
                "SELECT has_table_privilege(current_user,'app.member','TRUNCATE')", Boolean.class))
        .isFalse();
  }
}
