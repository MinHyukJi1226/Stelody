package com.stelody.export.repository;

import com.stelody.catalog.repository.PublicCatalogSql;
import com.stelody.export.dto.ExportDtos;
import com.stelody.export.web.ExportException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ExportRepository {
  public static final long PROCESS_LOCK = 2026100301L;

  public record Connection(
      UUID userId,
      UUID generation,
      String status,
      String access,
      String refresh,
      Instant expiresAt) {}

  public record Authorization(UUID generation, String verifier, String nonce) {}

  public record Task(
      UUID id,
      UUID userId,
      String name,
      String status,
      String playlistId,
      boolean inFlight,
      Integer inFlightPosition) {}

  public record Item(int position, UUID songId, String youtubeId, String status) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;

  public ExportRepository(JdbcClient jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    transaction = new TransactionTemplate(manager);
  }

  public <T> T tx(Supplier<T> action) {
    return transaction.execute(s -> action.get());
  }

  public boolean tryProcessingLock() {
    return jdbc.sql("SELECT pg_try_advisory_xact_lock(:lock)")
        .param("lock", PROCESS_LOCK)
        .query(Boolean.class)
        .single();
  }

  public String lockActive(UUID user) {
    var row =
        jdbc.sql("SELECT google_subject,status FROM app.app_user WHERE id=:user FOR UPDATE")
            .param("user", user)
            .query((r, n) -> Map.entry(r.getString(1), r.getString(2)))
            .optional();
    if (row.isEmpty() || !row.get().getValue().equals("ACTIVE"))
      throw new ExportException(401, "SESSION_EXPIRED");
    return row.get().getKey();
  }

  public Optional<Connection> connection(UUID user) {
    return jdbc.sql("SELECT * FROM app.youtube_connection WHERE user_id=:user")
        .param("user", user)
        .query(
            (r, n) ->
                new Connection(
                    user,
                    r.getObject("generation", UUID.class),
                    r.getString("status"),
                    r.getString("access_token"),
                    r.getString("refresh_token"),
                    r.getTimestamp("expires_at") == null
                        ? null
                        : r.getTimestamp("expires_at").toInstant()))
        .optional();
  }

  public void authorization(
      UUID user,
      UUID generation,
      String state,
      String session,
      String verifier,
      String nonce,
      Instant expiry) {
    jdbc.sql("DELETE FROM app.youtube_authorization WHERE expires_at<=CURRENT_TIMESTAMP").update();
    jdbc.sql(
            """
        INSERT INTO app.youtube_connection(user_id,generation,status) VALUES(:user,:gen,'DISCONNECTED')
        ON CONFLICT(user_id) DO UPDATE SET generation=:gen,updated_at=CURRENT_TIMESTAMP
        """)
        .param("user", user)
        .param("gen", generation)
        .update();
    jdbc.sql(
            """
        INSERT INTO app.youtube_authorization(user_id,generation,state_hash,session_hash,verifier,nonce,expires_at)
        VALUES(:user,:gen,:state,:session,:verifier,:nonce,:expiry)
        ON CONFLICT(user_id) DO UPDATE SET generation=:gen,state_hash=:state,session_hash=:session,
          verifier=:verifier,nonce=:nonce,expires_at=:expiry
        """)
        .param("user", user)
        .param("gen", generation)
        .param("state", state)
        .param("session", session)
        .param("verifier", verifier)
        .param("nonce", nonce)
        .param("expiry", Timestamp.from(expiry))
        .update();
  }

  public Authorization consume(UUID user, String state, String session) {
    return jdbc.sql(
            """
        DELETE FROM app.youtube_authorization WHERE user_id=:user AND state_hash=:state
          AND session_hash=:session AND expires_at>CURRENT_TIMESTAMP
        RETURNING generation,verifier,nonce
        """)
        .param("user", user)
        .param("state", state)
        .param("session", session)
        .query(
            (r, n) -> new Authorization(r.getObject(1, UUID.class), r.getString(2), r.getString(3)))
        .optional()
        .orElseThrow(() -> new ExportException(400, "INVALID_YOUTUBE_STATE"));
  }

  public boolean connect(
      UUID user, UUID generation, String access, String refresh, Instant expiry) {
    return jdbc.sql(
                """
        UPDATE app.youtube_connection SET status='CONNECTED',access_token=:access,refresh_token=:refresh,
          expires_at=:expiry,updated_at=CURRENT_TIMESTAMP WHERE user_id=:user AND generation=:gen
          AND status<>'REVOKING'
        """)
            .param("user", user)
            .param("gen", generation)
            .param("access", access)
            .param("refresh", refresh)
            .param("expiry", Timestamp.from(expiry))
            .update()
        == 1;
  }

  public void invalidate(UUID user) {
    deleteAuthorizedData(user);
    jdbc.sql(
            """
        UPDATE app.youtube_connection SET status='RECONNECT_REQUIRED',access_token=NULL,
          refresh_token=NULL,expires_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=:user
        """)
        .param("user", user)
        .update();
  }

  public Connection disconnect(UUID user) {
    lockActive(user);
    deleteAuthorizedData(user);
    jdbc.sql(
            """
        UPDATE app.youtube_connection SET generation=:gen,access_token=NULL,expires_at=NULL,
          status=CASE WHEN refresh_token IS NULL THEN 'DISCONNECTED' ELSE 'REVOKING' END,
          updated_at=CURRENT_TIMESTAMP WHERE user_id=:user
        """)
        .param("user", user)
        .param("gen", UUID.randomUUID())
        .update();
    return connection(user).orElse(null);
  }

  private void deleteAuthorizedData(UUID user) {
    jdbc.sql("DELETE FROM app.youtube_authorization WHERE user_id=:user")
        .param("user", user)
        .update();
    // Cascading deletion also removes video IDs and copied item results.
    jdbc.sql("DELETE FROM app.youtube_export WHERE user_id=:user").param("user", user).update();
  }

  public List<Connection> revoking() {
    return jdbc
        .sql(
            "SELECT user_id FROM app.youtube_connection WHERE status='REVOKING' ORDER BY updated_at,user_id LIMIT 1")
        .query(UUID.class)
        .list()
        .stream()
        .map(u -> connection(u).orElseThrow())
        .toList();
  }

  public boolean revocationAttempt(Connection value) {
    return jdbc.sql(
                "UPDATE app.youtube_connection SET updated_at=CURRENT_TIMESTAMP WHERE user_id=:user AND generation=:gen AND status='REVOKING'")
            .param("user", value.userId())
            .param("gen", value.generation())
            .update()
        == 1;
  }

  public void revoked(Connection value) {
    int changed =
        jdbc.sql(
                """
        UPDATE app.youtube_connection SET status='DISCONNECTED',access_token=NULL,refresh_token=NULL,
          expires_at=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE user_id=:user AND generation=:gen AND status='REVOKING'
        """)
            .param("user", value.userId())
            .param("gen", value.generation())
            .update();
    if (changed == 1) deleteAuthorizedData(value.userId());
  }

  public void cleanup() {
    jdbc.sql("DELETE FROM app.youtube_authorization WHERE expires_at<=CURRENT_TIMESTAMP").update();
    jdbc.sql("DELETE FROM app.youtube_export WHERE created_at<CURRENT_TIMESTAMP-interval '30 days'")
        .update();
    jdbc.sql(
            """
        UPDATE app.youtube_connection SET status='REVOKING',generation=gen_random_uuid()
        WHERE status='CONNECTED' AND (updated_at<CURRENT_TIMESTAMP-interval '30 days'
          OR EXISTS(SELECT 1 FROM app.app_user u WHERE u.id=user_id AND u.status<>'ACTIVE'))
        """)
        .update();
    jdbc.sql(
            "UPDATE app.youtube_export SET status='CANCELLED',error_code='YOUTUBE_DISCONNECTED',updated_at=CURRENT_TIMESTAMP WHERE status IN ('QUEUED','RUNNING','FAILED','UNCERTAIN') AND user_id IN (SELECT user_id FROM app.youtube_connection WHERE status='REVOKING')")
        .update();
  }

  public ExportDtos.Job create(UUID user, UUID playlist, UUID request, long version) {
    lockActive(user);
    var existing =
        jdbc.sql(
                "SELECT id,source_playlist_id,source_version FROM app.youtube_export WHERE user_id=:user AND request_id=:request")
            .param("user", user)
            .param("request", request)
            .query(
                (r, n) ->
                    new Object[] {
                      r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3)
                    })
            .optional();
    if (existing.isPresent()) {
      var row = existing.get();
      if (!playlist.equals(row[1]) || version != (long) row[2])
        throw new ExportException(409, "EXPORT_REQUEST_REUSED");
      return job(user, (UUID) row[0]);
    }
    var source =
        jdbc.sql("SELECT name,version FROM app.playlist WHERE user_id=:user AND id=:id FOR UPDATE")
            .param("user", user)
            .param("id", playlist)
            .query((r, n) -> Map.entry(r.getString(1), r.getLong(2)))
            .optional()
            .orElseThrow(() -> new ExportException(404, "PLAYLIST_NOT_FOUND"));
    if (source.getValue() != version) throw new ExportException(409, "PLAYLIST_CHANGED");
    if (connection(user).filter(c -> c.status().equals("CONNECTED")).isEmpty())
      throw new ExportException(409, "YOUTUBE_CONNECTION_REQUIRED");
    if (jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM app.youtube_export WHERE user_id=:user AND status IN ('QUEUED','RUNNING','FAILED','UNCERTAIN'))")
        .param("user", user)
        .query(Boolean.class)
        .single()) throw new ExportException(409, "EXPORT_ALREADY_ACTIVE");
    var rows =
        jdbc.sql(
                PublicCatalogSql.SONGS
                    + """
        SELECT i.position,i.song_id,s.youtube_id FROM app.playlist_item i
        LEFT JOIN public_songs s ON s.id=i.song_id WHERE i.playlist_id=:id ORDER BY i.position
        """)
            .param("id", playlist)
            .query(
                (r, n) ->
                    new Item(
                        r.getInt(1),
                        r.getObject(2, UUID.class),
                        r.getString(3),
                        r.getString(3) == null ? "SKIPPED" : "PENDING"))
            .list();
    if (rows.size() > 500) throw new ExportException(400, "EXPORT_ITEM_LIMIT");
    if (rows.stream().noneMatch(r -> r.status().equals("PENDING")))
      throw new ExportException(409, "NO_EXPORTABLE_SONGS");
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO app.youtube_export(id,user_id,request_id,source_playlist_id,source_version,name,status)
        VALUES(:id,:user,:request,:playlist,:version,:name,'QUEUED')
        """)
        .param("id", id)
        .param("user", user)
        .param("request", request)
        .param("playlist", playlist)
        .param("version", version)
        .param("name", source.getKey())
        .update();
    for (var row : rows)
      jdbc.sql(
              "INSERT INTO app.youtube_export_item(export_id,position,song_id,youtube_id,status) VALUES(:id,:pos,:song,:youtube,:status)")
          .param("id", id)
          .param("pos", row.position())
          .param("song", row.songId())
          .param("youtube", row.youtubeId())
          .param("status", row.status())
          .update();
    return job(user, id);
  }

  public ExportDtos.Job job(UUID user, UUID id) {
    return jdbc.sql(
            """
        SELECT e.*,count(i.position) AS total,count(*) FILTER (WHERE i.status='COPIED') AS copied,
          count(*) FILTER (WHERE i.status='SKIPPED') AS skipped,count(*) FILTER(WHERE i.status='PENDING') AS remaining
        FROM app.youtube_export e LEFT JOIN app.youtube_export_item i ON i.export_id=e.id
        WHERE e.id=:id AND e.user_id=:user GROUP BY e.id
        """)
        .param("id", id)
        .param("user", user)
        .query(
            (r, n) -> {
              String external = r.getString("youtube_playlist_id");
              return new ExportDtos.Job(
                  id,
                  r.getObject("source_playlist_id", UUID.class),
                  r.getLong("source_version"),
                  r.getString("status"),
                  r.getInt("total"),
                  r.getInt("copied"),
                  r.getInt("skipped"),
                  r.getInt("remaining"),
                  external,
                  external == null ? null : "https://www.youtube.com/playlist?list=" + external,
                  r.getString("error_code"),
                  r.getTimestamp("created_at").toInstant(),
                  r.getTimestamp("updated_at").toInstant());
            })
        .optional()
        .orElseThrow(ExportException::missing);
  }

  public ExportDtos.Job retry(UUID user, UUID id) {
    lockActive(user);
    var job = job(user, id);
    if (job.status().equals("QUEUED")
        || job.status().equals("RUNNING")
        || job.status().equals("SUCCEEDED")) return job;
    if (!List.of("FAILED", "UNCERTAIN").contains(job.status()))
      throw new ExportException(409, "EXPORT_NOT_RETRYABLE");
    if (connection(user).filter(c -> c.status().equals("CONNECTED")).isEmpty())
      throw new ExportException(409, "YOUTUBE_CONNECTION_REQUIRED");
    jdbc.sql(
            "UPDATE app.youtube_export SET status='QUEUED',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
        .param("id", id)
        .update();
    return job(user, id);
  }

  public ExportDtos.Job cancel(UUID user, UUID id) {
    lockActive(user);
    var job = job(user, id);
    if (!job.status().equals("SUCCEEDED"))
      jdbc.sql(
              "UPDATE app.youtube_export SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=:id")
          .param("id", id)
          .update();
    return job(user, id);
  }

  public boolean inFlight(UUID id) {
    return jdbc.sql("SELECT in_flight FROM app.youtube_export WHERE id=:id")
        .param("id", id)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  public Optional<Task> next() {
    return jdbc.sql(
            "SELECT * FROM app.youtube_export WHERE status IN ('QUEUED','RUNNING') ORDER BY created_at,id LIMIT 1")
        .query(
            (r, n) ->
                new Task(
                    r.getObject("id", UUID.class),
                    r.getObject("user_id", UUID.class),
                    r.getString("name"),
                    r.getString("status"),
                    r.getString("youtube_playlist_id"),
                    r.getBoolean("in_flight"),
                    (Integer) r.getObject("in_flight_position")))
        .optional();
  }

  public List<Item> items(UUID id) {
    return jdbc.sql(
            "SELECT position,song_id,youtube_id,status FROM app.youtube_export_item WHERE export_id=:id ORDER BY position")
        .param("id", id)
        .query(
            (r, n) ->
                new Item(r.getInt(1), r.getObject(2, UUID.class), r.getString(3), r.getString(4)))
        .list();
  }

  public boolean available(Item item) {
    return jdbc.sql(
            PublicCatalogSql.SONGS
                + " SELECT EXISTS(SELECT 1 FROM public_songs WHERE id=:song AND youtube_id=:youtube)")
        .param("song", item.songId())
        .param("youtube", item.youtubeId())
        .query(Boolean.class)
        .single();
  }

  public boolean start(Task task) {
    lockActive(task.userId());
    if (connection(task.userId()).filter(c -> c.status().equals("CONNECTED")).isEmpty())
      throw new ExportException(409, "YOUTUBE_CONNECTION_REQUIRED");
    return jdbc.sql(
                "UPDATE app.youtube_export SET status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status IN ('QUEUED','RUNNING')")
            .param("id", task.id())
            .update()
        == 1;
  }

  public boolean beginWrite(Task task, Integer position) {
    lockActive(task.userId());
    if (connection(task.userId()).filter(c -> c.status().equals("CONNECTED")).isEmpty())
      return false;
    return jdbc.sql(
                "UPDATE app.youtube_export SET in_flight=true,in_flight_position=:pos,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='RUNNING' AND NOT in_flight")
            .param("id", task.id())
            .param("pos", position)
            .update()
        == 1;
  }

  public void created(Task task, String external) {
    jdbc.sql(
            "UPDATE app.youtube_export SET youtube_playlist_id=:external,in_flight=false,in_flight_position=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
        .param("id", task.id())
        .param("external", external)
        .update();
  }

  public void itemDone(Task task, int position, String status) {
    jdbc.sql(
            "UPDATE app.youtube_export_item SET status=:status WHERE export_id=:id AND position=:pos")
        .param("status", status)
        .param("id", task.id())
        .param("pos", position)
        .update();
    jdbc.sql(
            "UPDATE app.youtube_export SET in_flight=false,in_flight_position=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
        .param("id", task.id())
        .update();
  }

  public void finish(Task task, String status, String code, boolean certain) {
    jdbc.sql(
            """
        UPDATE app.youtube_export SET status=:status,error_code=:code,
          in_flight=CASE WHEN :certain THEN false ELSE in_flight END,
          in_flight_position=CASE WHEN :certain THEN NULL ELSE in_flight_position END,updated_at=CURRENT_TIMESTAMP
        WHERE id=:id AND status<>'CANCELLED'
        """)
        .param("id", task.id())
        .param("status", status)
        .param("code", code)
        .param("certain", certain)
        .update();
  }
}
