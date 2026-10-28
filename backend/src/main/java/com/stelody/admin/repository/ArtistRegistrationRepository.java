package com.stelody.admin.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ArtistRegistrationRepository {
  private final JdbcClient jdbc;

  public ArtistRegistrationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Receipt(
      UUID id,
      UUID actor,
      String hash,
      String status,
      UUID artistId,
      String errorCode,
      Integer errorStatus,
      Instant createdAt,
      Instant expiresAt,
      Instant finishedAt,
      boolean expired) {}

  public void accept(UUID id, UUID actor, String hash) {
    jdbc.sql(
            """
        INSERT INTO app.artist_registration_request(id,actor_id,body_hash)
        SELECT :id,id,:hash FROM app.app_user WHERE id=:actor AND role='ADMIN' AND status='ACTIVE'
        ON CONFLICT(id) DO NOTHING
        """)
        .param("id", id)
        .param("actor", actor)
        .param("hash", hash)
        .update();
  }

  public Optional<Receipt> find(UUID id) {
    return read(id, false);
  }

  public Optional<Receipt> lock(UUID id) {
    return read(id, true);
  }

  private Optional<Receipt> read(UUID id, boolean lock) {
    return jdbc.sql(
            "SELECT *,expires_at<=clock_timestamp() OR status='EXPIRED' AS expired FROM app.artist_registration_request WHERE id=:id"
                + (lock ? " FOR UPDATE SKIP LOCKED" : ""))
        .param("id", id)
        .query(
            (rs, n) ->
                new Receipt(
                    rs.getObject("id", UUID.class),
                    rs.getObject("actor_id", UUID.class),
                    rs.getString("body_hash"),
                    rs.getString("status"),
                    rs.getObject("artist_id", UUID.class),
                    rs.getString("error_code"),
                    rs.getObject("error_status", Integer.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("finished_at") == null
                        ? null
                        : rs.getTimestamp("finished_at").toInstant(),
                    rs.getBoolean("expired")))
        .optional();
  }

  public void complete(UUID id, UUID artist) {
    if (jdbc.sql(
                """
        UPDATE app.artist_registration_request SET status='COMPLETED',artist_id=:artist,finished_at=clock_timestamp()
        WHERE id=:id AND status='PROCESSING'
        """)
            .param("id", id)
            .param("artist", artist)
            .update()
        != 1) throw new IllegalStateException("Artist registration receipt was not processing");
  }

  public void fail(UUID id, UUID actor, String hash, String code, int status) {
    jdbc.sql(
            """
        UPDATE app.artist_registration_request SET status='FAILED',error_code=:code,error_status=:status,
          finished_at=clock_timestamp()
        WHERE id=:id AND actor_id=:actor AND body_hash=:hash AND status='PROCESSING'
        """)
        .param("id", id)
        .param("actor", actor)
        .param("hash", hash)
        .param("code", code)
        .param("status", status)
        .update();
  }

  public int maintain() {
    // No work transaction may be active on these rows: a live creation holds its receipt lock.
    int stalled =
        jdbc.sql(
                """
        WITH selected AS (
          SELECT id FROM app.artist_registration_request
          WHERE status='PROCESSING' AND created_at<clock_timestamp()-INTERVAL '2 minutes'
            AND expires_at>clock_timestamp()
          ORDER BY created_at LIMIT 1000 FOR UPDATE SKIP LOCKED
        )
        UPDATE app.artist_registration_request r SET status='FAILED',error_code='ARTIST_REGISTRATION_INTERRUPTED',
          error_status=503,finished_at=clock_timestamp()
        FROM selected s WHERE r.id=s.id
        """)
            .update();
    int expired =
        jdbc.sql(
                """
        WITH selected AS (
          SELECT id FROM app.artist_registration_request WHERE expires_at<=clock_timestamp() AND status<>'EXPIRED'
          ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED
        )
        UPDATE app.artist_registration_request r SET status='EXPIRED',artist_id=NULL,error_code=NULL,error_status=NULL,finished_at=NULL
        FROM selected s WHERE r.id=s.id
        """)
            .update();
    return stalled + expired;
  }
}
