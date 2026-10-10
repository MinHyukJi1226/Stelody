package com.stelody.admin.service;

import com.stelody.admin.dto.CatalogAdminDtos.AuditPage;
import com.stelody.admin.dto.CoverPublicationDtos.*;
import com.stelody.admin.repository.CatalogAdminQueries;
import com.stelody.admin.repository.SongInformationSql;
import com.stelody.admin.web.AdminCatalogException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoverPublicationService {
  private static final UUID ID = new UUID(0, 1);
  private final JdbcClient jdbc;
  private final CatalogAdminQueries catalog;
  private final boolean policyAllowed;

  public CoverPublicationService(
      JdbcClient jdbc,
      CatalogAdminQueries catalog,
      @Value("${stelody.cover-publication.policy-allowed:false}") boolean policyAllowed) {
    this.jdbc = jdbc;
    this.catalog = catalog;
    this.policyAllowed = policyAllowed;
  }

  @Transactional(readOnly = true)
  public Control current() {
    return jdbc.sql("SELECT enabled,version FROM app.cover_publication_control WHERE singleton")
        .query((rs, n) -> new Control(rs.getBoolean(1), rs.getLong(2), policyAllowed))
        .single();
  }

  @Transactional(timeout = 10)
  public Control change(Change input, UUID actor) {
    var before = current();
    if (input.enabled() && !policyAllowed)
      throw new AdminCatalogException(
          503, "COVER_PUBLICATION_POLICY_DISABLED", "커버 자동 공개 정책 설정이 필요합니다");
    if (before.version() != input.version()
        || jdbc.sql(
                    "UPDATE app.cover_publication_control SET enabled=:enabled,version=version+1,edited_at=now() WHERE singleton AND version=:version")
                .param("enabled", input.enabled())
                .param("version", input.version())
                .update()
            != 1) throw AdminCatalogException.conflict();
    var after = current();
    catalog.audit("COVER_PUBLICATION", ID, actor, "UPDATE", before, after, input.reason().strip());
    return after;
  }

  @Transactional(readOnly = true)
  public AuditPage audits(int page, int size) {
    page(page, size);
    var rows = catalog.audits("COVER_PUBLICATION", ID, page, size);
    return new AuditPage(rows.stream().limit(size).toList(), page, size, rows.size() > size);
  }

  @Transactional(readOnly = true)
  public Page registrations(boolean incompleteOnly, int page, int size) {
    page(page, size);
    var rows =
        jdbc.sql(
                """
        SELECT a.*,s.title,s.visibility,
        """
                    + SongInformationSql.SUPPLEMENTAL_MISSING_FIELDS
                    + """
          AS missing
        FROM app.cover_auto_registration a JOIN app.song_entry s ON s.id=a.song_id
        """
                    + (incompleteOnly
                        ? " WHERE (" + SongInformationSql.INCOMPLETE_AUTO_REGISTRATION + ")"
                        : "")
                    + " ORDER BY a.processed_at DESC,a.review_id DESC LIMIT :limit OFFSET :offset")
            .param("limit", size + 1)
            .param("offset", page * size)
            .query(
                (rs, n) ->
                    new Registration(
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("song_id", UUID.class),
                        rs.getObject("video_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("visibility"),
                        rs.getString("rule_version"),
                        rs.getString("reason"),
                        rs.getTimestamp("processed_at").toInstant(),
                        List.of((String[]) rs.getArray("missing").getArray())))
            .list();
    return new Page(rows.stream().limit(size).toList(), page, size, rows.size() > size);
  }

  private void page(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 50) throw AdminCatalogException.invalid();
  }
}
