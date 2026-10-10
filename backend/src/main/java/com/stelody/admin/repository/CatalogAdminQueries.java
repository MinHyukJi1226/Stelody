package com.stelody.admin.repository;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.web.AdminCatalogException;
import com.stelody.catalog.domain.SearchText;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CatalogAdminQueries {
  public enum Resource {
    MEMBERS("member", "name", "search_name"),
    ARTISTS("artist", "name", "search_name"),
    WORKS("musical_work", "title", "search_title"),
    SONGS("song_entry", "title", "search_title"),
    CHANNELS("channel", "name", null);
    public final String table, name, search;

    Resource(String table, String name, String search) {
      this.table = table;
      this.name = name;
      this.search = search;
    }

    public static Resource parse(String value) {
      try {
        return valueOf(value.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw AdminCatalogException.missing();
      }
    }
  }

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  public CatalogAdminQueries(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public List<Summary> list(Resource resource, int page, int size, String q) {
    String status =
        switch (resource) {
          case MEMBERS -> "activity_status";
          case SONGS -> "visibility";
          case CHANNELS -> "channel_type";
          default -> "NULL";
        };
    String aliasStem =
        switch (resource) {
          case MEMBERS -> "member";
          case ARTISTS -> "artist";
          case WORKS -> "work";
          case SONGS -> "song";
          case CHANNELS -> null;
        };
    String match =
        (resource.search == null ? "lower(c.name)" : "c." + resource.search)
            + " LIKE :q ESCAPE '!'";
    if (aliasStem != null)
      match +=
          " OR EXISTS (SELECT 1 FROM app."
              + aliasStem
              + "_alias a WHERE a."
              + aliasStem
              + "_id=c.id AND a.search_alias LIKE :q ESCAPE '!')";
    else match += " OR lower(c.youtube_id) LIKE :q ESCAPE '!'";
    return jdbc.sql(
            "SELECT c.id,c."
                + resource.name
                + " AS name,c.version,"
                + (status.equals("NULL") ? "NULL" : "c." + status)
                + " AS status FROM app."
                + resource.table
                + " c WHERE ("
                + match
                + ") ORDER BY c."
                + resource.name
                + ",c.id LIMIT :limit OFFSET :offset")
        .param("q", SearchText.likePattern(SearchText.normalize(q)))
        .param("limit", size + 1)
        .param("offset", page * size)
        .query(Summary.class)
        .list();
  }

  public List<UUID> duplicates(Resource resource, UUID id, String normalized) {
    return jdbc.sql(
            "SELECT id FROM app."
                + resource.table
                + " WHERE "
                + resource.search
                + "=:name AND id<>:id ORDER BY id LIMIT 20")
        .param("name", normalized)
        .param("id", id)
        .query(UUID.class)
        .list();
  }

  public List<SongSummary> songs(int page, int size, String q) {
    return jdbc.sql(
            """
        WITH selected AS (
          SELECT s.* FROM app.song_entry s
          WHERE s.search_title LIKE :q ESCAPE '!'
            OR EXISTS(SELECT 1 FROM app.song_alias a WHERE a.song_id=s.id AND a.search_alias LIKE :q ESCAPE '!')
          ORDER BY s.title,s.id LIMIT :limit OFFSET :offset
        )
        SELECT s.id,s.title,s.version,s.visibility,
          coalesce((SELECT jsonb_agg(to_jsonb(p) - 'category' - 'position' ORDER BY category,position,id)
            FROM (
              SELECT m.id,m.name,'MEMBER' AS kind,sm.confirmed,0 AS category,sm.position
              FROM app.song_member sm JOIN app.member m ON m.id=sm.member_id WHERE sm.song_id=s.id
              UNION ALL
              SELECT a.id,a.name,'EXTERNAL' AS kind,sa.confirmed,1 AS category,sa.position
              FROM app.song_external_artist sa JOIN app.artist a ON a.id=sa.artist_id WHERE sa.song_id=s.id
            ) p), '[]'::jsonb) AS participants,
          (SELECT min(r.first_seen_at) FROM app.video v JOIN app.review_item r ON r.youtube_id=v.youtube_id
            WHERE v.song_id=s.id) AS discovered_at,
        """
                + SongInformationSql.SUPPLEMENTAL_MISSING_FIELDS
                + """
          || array_remove(ARRAY[
            CASE WHEN NOT EXISTS(SELECT 1 FROM app.song_member sm WHERE sm.song_id=s.id) THEN 'members' END,
            CASE WHEN s.representative_video_id IS NULL THEN 'representativeVideo' END
          ],NULL) AS missing
        FROM selected s ORDER BY s.title,s.id
        """)
        .param("q", SearchText.likePattern(SearchText.normalize(q)))
        .param("limit", size + 1)
        .param("offset", page * size)
        .query(
            (r, n) -> {
              var missing = List.of((String[]) r.getArray("missing").getArray());
              return new SongSummary(
                  r.getObject("id", UUID.class),
                  r.getString("title"),
                  r.getLong("version"),
                  Visibility.valueOf(r.getString("visibility")),
                  List.of(mapper.readValue(r.getString("participants"), SongParticipant[].class)),
                  instant(r, "discovered_at"),
                  missing,
                  missing.stream().allMatch("aliases"::equals));
            })
        .list();
  }

  public Member member(UUID id) {
    return jdbc.sql("SELECT * FROM app.member WHERE id=:id")
        .param("id", id)
        .query(
            (r, n) ->
                new Member(
                    id,
                    r.getLong("version"),
                    r.getString("name"),
                    r.getObject("generation", Integer.class),
                    r.getString("unit_name"),
                    r.getString("chzzk_url"),
                    r.getString("x_url"),
                    r.getString("activity_status"),
                    r.getString("profile_image_url"),
                    r.getObject("debut_date", java.time.LocalDate.class),
                    r.getObject("birthday_month", Integer.class),
                    r.getObject("birthday_day", Integer.class),
                    aliases("member_alias", "member_id", id)))
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  public Artist artist(UUID id) {
    return jdbc.sql("SELECT * FROM app.artist WHERE id=:id")
        .param("id", id)
        .query(
            (r, n) ->
                new Artist(
                    id,
                    r.getLong("version"),
                    r.getString("name"),
                    aliases("artist_alias", "artist_id", id)))
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  public Work work(UUID id) {
    return jdbc.sql("SELECT * FROM app.musical_work WHERE id=:id")
        .param("id", id)
        .query(
            (r, n) ->
                new Work(
                    id,
                    r.getLong("version"),
                    r.getString("title"),
                    aliases("work_alias", "work_id", id),
                    relation("work_artist", "work_id", "artist_id", id),
                    links(false, id),
                    karaoke(false, id)))
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  public Channel channel(UUID id) {
    return jdbc.sql(
            "SELECT id,version,youtube_id,name,member_id,channel_type,collection_enabled FROM app.channel WHERE id=:id")
        .param("id", id)
        .query(Channel.class)
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  public Song song(UUID id) {
    return jdbc.sql("SELECT * FROM app.song_entry WHERE id=:id")
        .param("id", id)
        .query(
            (r, n) -> {
              var memberIds = relation("song_member", "song_id", "member_id", id);
              var missing = new ArrayList<String>();
              if (r.getObject("work_id") == null) missing.add("work");
              if (memberIds.isEmpty()) missing.add("members");
              if (r.getObject("representative_video_id") == null)
                missing.add("representativeVideo");
              if ("UNCHECKED".equals(r.getString("search_visibility"))) missing.add("searchCheck");
              return new Song(
                  id,
                  r.getLong("version"),
                  r.getString("title"),
                  r.getString("song_type"),
                  r.getObject("work_id", UUID.class),
                  r.getString("visibility"),
                  r.getObject("representative_video_id", UUID.class),
                  aliases("song_alias", "song_id", id),
                  memberIds,
                  relation("song_external_artist", "song_id", "artist_id", id),
                  r.getBoolean("is_special_event"),
                  r.getString("special_event_label"),
                  r.getString("search_visibility"),
                  r.getString("recommended_search_query"),
                  instant(r, "search_checked_at"),
                  videos(id),
                  links(true, id),
                  karaoke(true, id),
                  missing);
            })
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  public Object detail(Resource resource, UUID id) {
    return switch (resource) {
      case MEMBERS -> member(id);
      case ARTISTS -> artist(id);
      case WORKS -> work(id);
      case SONGS -> song(id);
      case CHANNELS -> channel(id);
    };
  }

  public List<Video> videos(UUID song) {
    return jdbc.sql("SELECT * FROM app.video WHERE song_id=:song ORDER BY id")
        .param("song", song)
        .query(this::mapVideo)
        .list();
  }

  public Video video(UUID id) {
    return jdbc.sql("SELECT * FROM app.video WHERE id=:id")
        .param("id", id)
        .query(this::mapVideo)
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  private Video mapVideo(ResultSet r, int n) throws SQLException {
    return new Video(
        r.getObject("id", UUID.class),
        r.getLong("version"),
        r.getObject("song_id", UUID.class),
        r.getString("youtube_id"),
        r.getString("video_kind"),
        r.getString("availability"),
        r.getString("source_title"),
        instant(r, "source_published_at"),
        r.getString("source_thumbnail_url"),
        instant(r, "source_observed_at"),
        instant(r, "published_at"),
        r.getString("thumbnail_url"),
        r.getBoolean("embeddable"));
  }

  public List<LinkView> links(boolean song, UUID id) {
    return jdbc.sql(
            "SELECT id,platform,url,source_url,checked_at FROM app.external_link WHERE "
                + (song ? "song_id" : "work_id")
                + "=:id ORDER BY platform,id")
        .param("id", id)
        .query(LinkView.class)
        .list();
  }

  public List<KaraokeView> karaoke(boolean song, UUID id) {
    return jdbc.sql(
            "SELECT id,provider,status,number,source_url,checked_at FROM app.karaoke_entry WHERE "
                + (song ? "song_id" : "work_id")
                + "=:id ORDER BY provider")
        .param("id", id)
        .query(KaraokeView.class)
        .list();
  }

  private List<String> aliases(String table, String fk, UUID id) {
    return jdbc.sql(
            "SELECT alias FROM app." + table + " WHERE " + fk + "=:id ORDER BY search_alias")
        .param("id", id)
        .query(String.class)
        .list();
  }

  private List<UUID> relation(String table, String fk, String target, UUID id) {
    return jdbc.sql(
            "SELECT " + target + " FROM app." + table + " WHERE " + fk + "=:id ORDER BY position")
        .param("id", id)
        .query(UUID.class)
        .list();
  }

  public void aliases(Resource resource, UUID id, List<String> values) {
    String stem =
        switch (resource) {
          case MEMBERS -> "member";
          case ARTISTS -> "artist";
          case WORKS -> "work";
          case SONGS -> "song";
          default -> throw AdminCatalogException.invalid();
        };
    jdbc.sql("DELETE FROM app." + stem + "_alias WHERE " + stem + "_id=:id")
        .param("id", id)
        .update();
    for (String value : values)
      jdbc.sql(
              "INSERT INTO app."
                  + stem
                  + "_alias("
                  + stem
                  + "_id,alias,search_alias) VALUES(:id,:value,:search)")
          .param("id", id)
          .param("value", value.strip())
          .param("search", SearchText.normalize(value))
          .update();
  }

  public void relation(String table, String fk, String target, UUID id, List<UUID> values) {
    jdbc.sql("DELETE FROM app." + table + " WHERE " + fk + "=:id").param("id", id).update();
    for (int n = 0; n < values.size(); n++)
      jdbc.sql(
              "INSERT INTO app."
                  + table
                  + "("
                  + fk
                  + ","
                  + target
                  + ",position) VALUES(:id,:target,:position)")
          .param("id", id)
          .param("target", values.get(n))
          .param("position", n)
          .update();
  }

  public void requireReferences(Resource resource, List<UUID> ids) {
    if (ids.isEmpty()) return;
    long count =
        jdbc.sql("SELECT count(*) FROM app." + resource.table + " WHERE id IN (:ids)")
            .param("ids", ids)
            .query(Long.class)
            .single();
    if (count != ids.size())
      throw new AdminCatalogException(400, "INVALID_CATALOG_REFERENCE", "연결할 항목을 확인해 주세요");
  }

  public void extras(boolean song, UUID id, List<Link> links, List<Karaoke> karaoke) {
    String fk = song ? "song_id" : "work_id";
    jdbc.sql("DELETE FROM app.external_link WHERE " + fk + "=:id").param("id", id).update();
    jdbc.sql("DELETE FROM app.karaoke_entry WHERE " + fk + "=:id").param("id", id).update();
    for (var link : links)
      jdbc.sql(
              "INSERT INTO app.external_link(id,"
                  + fk
                  + ",platform,url,source_url,checked_at) VALUES(:key,:id,:platform,:url,:source,CURRENT_TIMESTAMP)")
          .param("key", UUID.randomUUID())
          .param("id", id)
          .param("platform", link.platform().strip())
          .param("url", link.url().strip())
          .param("source", link.sourceUrl().strip())
          .update();
    for (var entry : karaoke)
      jdbc.sql(
              "INSERT INTO app.karaoke_entry(id,"
                  + fk
                  + ",provider,status,number,source_url,checked_at) VALUES(:key,:id,:provider,:status,:number,:source,:checked)")
          .param("key", UUID.randomUUID())
          .param("id", id)
          .param("provider", entry.provider().name())
          .param("status", entry.status().name())
          .param("number", entry.number() == null ? null : entry.number().strip())
          .param("source", entry.sourceUrl() == null ? null : entry.sourceUrl().strip())
          .param(
              "checked",
              entry.status() == KaraokeStatus.UNKNOWN ? null : Timestamp.from(Instant.now()))
          .update();
  }

  public void audit(
      String kind, UUID id, UUID actor, String action, Object before, Object after, String reason) {
    jdbc.sql(
            "INSERT INTO app.catalog_audit(id,target_type,target_id,actor_id,action,before_value,after_value,reason) VALUES(:key,:kind,:id,:actor,:action,CAST(:before AS jsonb),CAST(:after AS jsonb),:reason)")
        .param("key", UUID.randomUUID())
        .param("kind", kind)
        .param("id", id)
        .param("actor", actor)
        .param("action", action)
        .param("before", before == null ? null : auditJson(before))
        .param("after", auditJson(after))
        .param("reason", reason.strip())
        .update();
  }

  private String auditJson(Object value) {
    var tree = mapper.valueToTree(value);
    if (value instanceof Song) ((tools.jackson.databind.node.ObjectNode) tree).remove("videos");
    if (value instanceof Video) {
      var object = (tools.jackson.databind.node.ObjectNode) tree;
      for (String field :
          List.of(
              "sourceTitle",
              "sourcePublishedAt",
              "sourceThumbnailUrl",
              "sourceObservedAt",
              "availability",
              "embeddable")) object.remove(field);
    }
    return mapper.writeValueAsString(tree);
  }

  public List<Audit> audits(String kind, UUID id, int page, int size) {
    return jdbc.sql(
            "SELECT * FROM app.catalog_audit WHERE target_type=:kind AND target_id=:id ORDER BY changed_at DESC,id DESC LIMIT :limit OFFSET :offset")
        .param("kind", kind)
        .param("id", id)
        .param("limit", size + 1)
        .param("offset", page * size)
        .query(
            (r, n) ->
                new Audit(
                    r.getObject("id", UUID.class),
                    r.getString("target_type"),
                    id,
                    r.getObject("actor_id", UUID.class),
                    r.getString("action"),
                    r.getString("before_value") == null
                        ? null
                        : mapper.readTree(r.getString("before_value")),
                    mapper.readTree(r.getString("after_value")),
                    r.getString("reason"),
                    instant(r, "changed_at")))
        .list();
  }

  public record ObservedCandidate(
      UUID id,
      String youtubeId,
      UUID channelId,
      String title,
      Instant publishedAt,
      String thumbnailUrl,
      Long durationSeconds,
      Instant observedAt,
      String availability,
      String status,
      long version,
      String channelType,
      boolean collectionEnabled) {}

  public ObservedCandidate candidate(UUID id) {
    return jdbc.sql(
            "SELECT r.*,c.channel_type,c.collection_enabled FROM app.review_item r JOIN app.channel c ON c.id=r.channel_id WHERE r.id=:id")
        .param("id", id)
        .query(
            (r, n) ->
                new ObservedCandidate(
                    id,
                    r.getString("youtube_id"),
                    r.getObject("channel_id", UUID.class),
                    r.getString("source_title"),
                    instant(r, "source_published_at"),
                    r.getString("source_thumbnail_url"),
                    r.getObject("source_duration_seconds", Long.class),
                    instant(r, "source_observed_at"),
                    r.getString("availability"),
                    r.getString("review_status"),
                    r.getLong("version"),
                    r.getString("channel_type"),
                    r.getBoolean("collection_enabled")))
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  public UUID candidateId(String youtubeId) {
    return jdbc.sql("SELECT id FROM app.review_item WHERE youtube_id=:youtube")
        .param("youtube", youtubeId)
        .query(UUID.class)
        .optional()
        .orElseThrow(
            () ->
                new AdminCatalogException(
                    409, "VIDEO_REQUIRES_OBSERVATION", "공식 채널에서 수집된 후보를 먼저 확인해 주세요"));
  }

  public Optional<UUID> registeredSong(String youtubeId) {
    return jdbc.sql("SELECT song_id FROM app.video WHERE youtube_id=:youtube")
        .param("youtube", youtubeId)
        .query(UUID.class)
        .optional();
  }

  public UUID insertVideo(UUID song, ObservedCandidate candidate, VideoKind kind) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
      INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title,
        source_published_at,source_thumbnail_url,source_duration_seconds,source_observed_at,status_observed_at,embeddable)
      VALUES(:id,:song,:youtube,:channel,:kind,:availability,:title,:published,:thumbnail,:duration,:observed,:observed,false)
      """)
        .param("id", id)
        .param("song", song)
        .param("youtube", candidate.youtubeId())
        .param("channel", candidate.channelId())
        .param("kind", kind.name())
        .param("availability", candidate.availability())
        .param("title", candidate.title())
        .param("published", Timestamp.from(candidate.publishedAt()))
        .param("thumbnail", candidate.thumbnailUrl())
        .param("duration", candidate.durationSeconds())
        .param("observed", Timestamp.from(candidate.observedAt()))
        .update();
    return id;
  }

  public void lockVideos(UUID song) {
    jdbc.sql("SELECT id FROM app.video WHERE song_id=:song ORDER BY id FOR UPDATE")
        .param("song", song)
        .query(UUID.class)
        .list();
  }

  public void lockChannel(UUID id) {
    jdbc.sql("SELECT id FROM app.channel WHERE id=:id FOR UPDATE")
        .param("id", id)
        .query(UUID.class)
        .optional()
        .orElseThrow(AdminCatalogException::missing);
  }

  private static Instant instant(ResultSet r, String column) throws SQLException {
    Timestamp t = r.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
