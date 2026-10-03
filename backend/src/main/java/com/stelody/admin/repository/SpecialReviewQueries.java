package com.stelody.admin.repository;

import com.stelody.admin.domain.AnniversaryRules;
import com.stelody.admin.domain.AnniversaryRules.*;
import com.stelody.admin.dto.SpecialReviewDtos.Status;
import com.stelody.admin.web.AdminCatalogException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SpecialReviewQueries {
  public record Record(
      UUID id,
      UUID songId,
      Status status,
      long version,
      List<Evidence> evidence,
      boolean active,
      Instant expiresAt,
      Instant createdAt) {}

  public record Basis(List<Evidence> evidence, Instant expiresAt) {}

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  public SpecialReviewQueries(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public record SongView(
      String title, long version, boolean isSpecialEvent, String specialEventLabel) {}

  public SongView song(UUID id) {
    return jdbc.sql(
            "SELECT title,version,is_special_event,special_event_label FROM app.song_entry WHERE id=:id")
        .param("id", id)
        .query(
            (r, n) -> new SongView(r.getString(1), r.getLong(2), r.getBoolean(3), r.getString(4)))
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  public UUID songId(UUID candidate) {
    return jdbc.sql("SELECT song_id FROM app.special_event_review WHERE id=:id")
        .param("id", candidate)
        .query(UUID.class)
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  public void lockSong(UUID song) {
    jdbc.sql("SELECT id FROM app.song_entry WHERE id=:id FOR UPDATE")
        .param("id", song)
        .query(UUID.class)
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  public Optional<Record> forSong(UUID song) {
    return jdbc.sql("SELECT * FROM app.special_event_review WHERE song_id=:id")
        .param("id", song)
        .query(this::record)
        .optional();
  }

  public Record get(UUID id) {
    return jdbc.sql("SELECT * FROM app.special_event_review WHERE id=:id")
        .param("id", id)
        .query(this::record)
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  private Record record(ResultSet r, int n) throws SQLException {
    return new Record(
        r.getObject("id", UUID.class),
        r.getObject("song_id", UUID.class),
        Status.valueOf(r.getString("status")),
        r.getLong("version"),
        List.of(mapper.readValue(r.getString("evidence"), Evidence[].class)),
        r.getBoolean("active"),
        r.getTimestamp("source_expires_at").toInstant(),
        r.getTimestamp("created_at").toInstant());
  }

  public List<Record> list(Status status, int page, int size) {
    return jdbc.sql(
            "SELECT * FROM app.special_event_review WHERE (:status IS NULL OR status=:status) ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
        .param("status", status == null ? null : status.name(), java.sql.Types.VARCHAR)
        .param("limit", size + 1)
        .param("offset", page * size)
        .query(this::record)
        .list();
  }

  public Basis basis(UUID song, Instant now, boolean lock) {
    // Song is locked first by all writers. Lock manual dates, observed source, then members.
    var video =
        jdbc.sql(
                "SELECT v.id,COALESCE(v.published_at,v.source_published_at) AS published,v.source_observed_at,v.availability FROM app.video v JOIN app.song_entry s ON s.representative_video_id=v.id AND s.id=v.song_id WHERE s.id=:song"
                    + (lock ? " FOR SHARE OF v" : ""))
            .param("song", song)
            .query(
                (r, n) ->
                    new VideoBasis(
                        r.getObject("id", UUID.class),
                        instant(r, "published"),
                        instant(r, "source_observed_at"),
                        r.getString("availability")))
            .optional();
    if (video.isEmpty()) return new Basis(List.of(), now);
    var v = video.get();
    if (!"PUBLIC".equals(v.availability())
        || v.published() == null
        || v.published().isAfter(now)
        || v.observed() == null
        || !v.observed().isAfter(now.minusSeconds(2592000))) return new Basis(List.of(), now);
    var participants =
        jdbc.sql(
                "SELECT m.id,m.name,m.birthday_month,m.birthday_day,m.debut_date FROM app.member m JOIN app.song_member sm ON sm.member_id=m.id WHERE sm.song_id=:song AND sm.confirmed ORDER BY sm.position,m.id"
                    + (lock ? " FOR SHARE OF m" : ""))
            .param("song", song)
            .query(
                (r, n) ->
                    new Participant(
                        r.getObject("id", UUID.class),
                        r.getString("name"),
                        r.getObject("birthday_month", Integer.class),
                        r.getObject("birthday_day", Integer.class),
                        r.getObject("debut_date", LocalDate.class)))
            .list();
    return new Basis(
        AnniversaryRules.match(v.published(), v.id(), participants),
        v.observed().plusSeconds(2592000));
  }

  private record VideoBasis(UUID id, Instant published, Instant observed, String availability) {}

  private static Instant instant(ResultSet r, String field) throws SQLException {
    Timestamp t = r.getTimestamp(field);
    return t == null ? null : t.toInstant();
  }

  public UUID refresh(UUID song, Basis basis) {
    var old = forSong(song);
    if (old.isEmpty()) {
      if (basis.evidence().isEmpty()) return null;
      UUID id = UUID.randomUUID();
      jdbc.sql(
              "INSERT INTO app.special_event_review(id,song_id,evidence,source_expires_at) VALUES(:id,:song,CAST(:evidence AS jsonb),:expires)")
          .param("id", id)
          .param("song", song)
          .param("evidence", mapper.writeValueAsString(basis.evidence()))
          .param("expires", Timestamp.from(basis.expiresAt()))
          .update();
      return id;
    }
    var previous = old.get();
    UUID matchedId = basis.evidence().isEmpty() ? null : previous.id();
    if (previous.status() != Status.PENDING) return matchedId;
    jdbc.sql(
            "UPDATE app.special_event_review SET evidence=CAST(:evidence AS jsonb),active=:active,source_expires_at=:expires,version=version+CASE WHEN evidence<>CAST(:evidence AS jsonb) OR active<>:active THEN 1 ELSE 0 END,updated_at=now() WHERE id=:id AND status='PENDING' AND version=:version")
        .param("evidence", mapper.writeValueAsString(basis.evidence()))
        .param("active", !basis.evidence().isEmpty())
        .param("expires", Timestamp.from(basis.expiresAt()))
        .param("id", previous.id())
        .param("version", previous.version())
        .update();
    return matchedId;
  }

  public void decide(Record before, Status status) {
    if (jdbc.sql(
                "UPDATE app.special_event_review SET status=:status,version=version+1,updated_at=now() WHERE id=:id AND version=:version")
            .param("status", status.name())
            .param("id", before.id())
            .param("version", before.version())
            .update()
        != 1) throw AdminCatalogException.conflict();
  }

  public void expire(Instant now) {
    jdbc.sql(
            "UPDATE app.special_event_review SET evidence='[]',active=false,version=version+1,updated_at=now() WHERE source_expires_at<=:now AND (active OR evidence<>'[]'::jsonb)")
        .param("now", Timestamp.from(now))
        .update();
  }

  private record Cursor(UUID last) {}

  public List<UUID> nextBatch() {
    UUID last =
        jdbc.sql("SELECT last_song_id FROM app.special_event_scan_state WHERE singleton FOR UPDATE")
            .query((r, n) -> new Cursor(r.getObject(1, UUID.class)))
            .single()
            .last();
    var ids =
        jdbc.sql(
                "SELECT id FROM app.song_entry WHERE (CAST(:last AS uuid) IS NULL OR id>CAST(:last AS uuid)) ORDER BY id LIMIT 50")
            .param("last", last, java.sql.Types.OTHER)
            .query(UUID.class)
            .list();
    jdbc.sql("UPDATE app.special_event_scan_state SET last_song_id=:last WHERE singleton")
        .param("last", ids.size() < 50 ? null : ids.getLast(), java.sql.Types.OTHER)
        .update();
    return ids;
  }

  public boolean scanLock() {
    return jdbc.sql("SELECT pg_try_advisory_xact_lock(2026100402)").query(Boolean.class).single();
  }
}
