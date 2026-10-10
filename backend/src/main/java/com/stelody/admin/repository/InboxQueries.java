package com.stelody.admin.repository;

import com.stelody.admin.dto.InboxDtos.Overview;
import com.stelody.collection.repository.CollectionQueries;
import com.stelody.review.repository.ReviewQueries;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InboxQueries {
  private final JdbcClient jdbc;

  public InboxQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Overview overview() {
    Instant to =
        jdbc.sql("SELECT CURRENT_TIMESTAMP")
            .query((r, n) -> r.getTimestamp(1).toInstant())
            .single();
    Instant from = to.minusSeconds(86400);
    return jdbc.sql(
            """
        SELECT
          (SELECT count(*) FROM app.review_item r WHERE r.review_status='PENDING' AND (
        """
                + ReviewQueries.EFFECTIVE_DISPOSITION
                + """
          )='REVIEW') AS new_reviews,
          (SELECT count(*) FROM app.cover_auto_registration a JOIN app.song_entry s ON s.id=a.song_id WHERE (
        """
                + SongInformationSql.INCOMPLETE_AUTO_REGISTRATION
                + """
          )) AS incomplete,
          (SELECT count(*) FROM app.special_event_review WHERE status='PENDING') AS special,
          (SELECT count(*) FROM app.collection_run r WHERE
        """
                + CollectionQueries.unresolvedFailureWhere("VIDEO")
                + ") + (SELECT count(*) FROM app.discovery_run r WHERE "
                + CollectionQueries.unresolvedFailureWhere("DISCOVERY")
                + ") AS failures")
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(
            (r, n) ->
                new Overview(
                    to,
                    from,
                    r.getLong("new_reviews"),
                    r.getLong("incomplete"),
                    r.getLong("special"),
                    r.getLong("failures")))
        .single();
  }
}
