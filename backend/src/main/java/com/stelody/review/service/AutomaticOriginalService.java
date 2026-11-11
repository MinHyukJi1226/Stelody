package com.stelody.review.service;

import com.stelody.admin.repository.CatalogAdminQueries;
import com.stelody.collector.domain.CollectedSongTitle;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.review.domain.OriginalCredits;
import com.stelody.review.domain.OriginalCredits.Evidence;
import com.stelody.review.youtube.YouTubeOriginalLookup;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AutomaticOriginalService {
  private final JdbcClient jdbc;
  private final YouTubeOriginalLookup lookup;
  private final ReviewOriginalService originals;
  private final CatalogAdminQueries catalog;
  private final TransactionTemplate transactions;

  private record Pending(
      UUID reviewId,
      UUID songId,
      UUID videoId,
      String youtubeId,
      String channelId,
      String title,
      String sourceTitle,
      boolean premiere,
      int attempts) {
    String table() {
      return premiere ? "app.premiere_auto_registration" : "app.cover_auto_registration";
    }
  }

  public AutomaticOriginalService(
      JdbcClient jdbc,
      YouTubeOriginalLookup lookup,
      ReviewOriginalService originals,
      CatalogAdminQueries catalog,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.lookup = lookup;
    this.originals = originals;
    this.catalog = catalog;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(10);
  }

  public void runNext() {
    var pending = next();
    if (pending == null) return;
    Evidence evidence;
    try {
      evidence = lookup.findAutomatic(target(pending)).orElse(null);
    } catch (CollectionFailure failure) {
      transactions.executeWithoutResult(status -> finish(pending, null, true));
      return;
    }
    transactions.executeWithoutResult(status -> finish(pending, evidence, false));
  }

  private Pending next() {
    return jdbc.sql(
            """
            WITH due AS (
              SELECT review_id,song_id,video_id,original_lookup_after,original_lookup_attempts,false AS premiere
              FROM app.cover_auto_registration WHERE original_lookup_after<=CURRENT_TIMESTAMP
              UNION ALL
              SELECT review_id,song_id,video_id,original_lookup_after,original_lookup_attempts,true AS premiere
              FROM app.premiere_auto_registration WHERE original_lookup_after<=CURRENT_TIMESTAMP
            )
            SELECT d.review_id,d.song_id,d.video_id,v.youtube_id,c.youtube_id,s.title,v.source_title,
              d.premiere,d.original_lookup_attempts
            FROM due d JOIN app.song_entry s ON s.id=d.song_id
              JOIN app.video v ON v.id=d.video_id JOIN app.channel c ON c.id=v.channel_id
            ORDER BY d.original_lookup_after,d.review_id,d.premiere LIMIT 1
            """)
        .query(
            (r, n) ->
                new Pending(
                    r.getObject(1, UUID.class),
                    r.getObject(2, UUID.class),
                    r.getObject(3, UUID.class),
                    r.getString(4),
                    r.getString(5),
                    r.getString(6),
                    r.getString(7),
                    r.getBoolean(8),
                    r.getInt(9)))
        .optional()
        .orElse(null);
  }

  private YouTubeOriginalLookup.Target target(Pending pending) {
    var titles = new LinkedHashSet<String>();
    String raw = OriginalCredits.text(pending.sourceTitle());
    int credit = raw.lastIndexOf('/');
    if (credit > raw.lastIndexOf(']')) raw = raw.substring(0, credit).strip();
    var original = CollectedSongTitle.originalCredit(raw);
    original
        .map(CollectedSongTitle.Credit::original)
        .map(OriginalCredits::unquoteTitle)
        .filter(OriginalCredits::validTitle)
        .ifPresent(titles::add);
    titles.addAll(OriginalCredits.titles(pending.title()));
    return new YouTubeOriginalLookup.Target(
        pending.reviewId(),
        pending.youtubeId(),
        pending.channelId(),
        List.copyOf(titles),
        original.map(CollectedSongTitle.Credit::artists).orElse(List.of()),
        null);
  }

  private void finish(Pending pending, Evidence evidence, boolean retry) {
    // Lock and recheck the publication and song before creating shared metadata.
    var eligible =
        jdbc.sql(
                """
            SELECT s.version=0 AND s.work_id IS NULL AND s.song_type='COVER'
              AND s.visibility='PUBLISHED' AND s.representative_video_id=d.video_id
              AND s.title=:title
            FROM %s d JOIN app.song_entry s ON s.id=d.song_id
            WHERE d.review_id=:id AND d.original_lookup_after<=CURRENT_TIMESTAMP
              AND d.original_lookup_attempts=:attempts
            FOR UPDATE OF d,s
            """
                    .formatted(pending.table()))
            .param("id", pending.reviewId())
            .param("attempts", pending.attempts())
            .param("title", pending.title())
            .query(Boolean.class)
            .optional();
    if (eligible.isEmpty()) return;
    if (Boolean.TRUE.equals(eligible.get()) && evidence != null) {
      var work = originals.storeAutomatic(evidence);
      if (work != null) {
        jdbc.sql(
                "UPDATE app.song_entry SET work_id=:work,version=version+1,edited_at=now() WHERE id=:song")
            .param("work", work.id())
            .param("song", pending.songId())
            .update();
        catalog.audit(
            "SONGS",
            pending.songId(),
            null,
            "SUPPLEMENT_ORIGINAL",
            Collections.singletonMap("workId", null),
            Map.of("workId", work.id()),
            "자동 등록 곡 원곡 연결 (" + evidence.sourceUrl() + ")");
      }
    }
    jdbc.sql(
            """
            UPDATE %s SET original_lookup_attempts=original_lookup_attempts+1,
              original_lookup_after=CASE WHEN :retry AND original_lookup_attempts<2
                THEN CURRENT_TIMESTAMP+interval '1 hour' ELSE NULL END
            WHERE review_id=:id
            """
                .formatted(pending.table()))
        .param("id", pending.reviewId())
        .param("retry", retry && Boolean.TRUE.equals(eligible.get()))
        .update();
  }
}
