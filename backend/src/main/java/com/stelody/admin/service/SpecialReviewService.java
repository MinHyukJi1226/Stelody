package com.stelody.admin.service;

import com.stelody.admin.domain.ManagedSong;
import com.stelody.admin.dto.CatalogAdminDtos.AuditPage;
import com.stelody.admin.dto.SpecialReviewDtos.*;
import com.stelody.admin.repository.CatalogAdminQueries;
import com.stelody.admin.repository.SpecialReviewQueries;
import com.stelody.admin.repository.SpecialReviewQueries.Record;
import com.stelody.admin.web.AdminCatalogException;
import com.stelody.catalog.domain.SearchText;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpecialReviewService {
  private final SpecialReviewQueries queries;
  private final CatalogAdminQueries catalog;
  private final EntityManager entities;
  private final boolean policyAllowed;

  public SpecialReviewService(
      SpecialReviewQueries queries,
      CatalogAdminQueries catalog,
      EntityManager entities,
      @Value("${stelody.special-review.policy-allowed:false}") boolean policyAllowed) {
    this.queries = queries;
    this.catalog = catalog;
    this.entities = entities;
    this.policyAllowed = policyAllowed;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page list(Status status, int page, int size) {
    page(page, size);
    var rows = queries.list(status, page, size);
    return new Page(
        rows.stream().limit(size).map(this::item).toList(), page, size, rows.size() > size);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Item detail(UUID id) {
    return item(queries.get(id));
  }

  private Item item(Record record) {
    var song = queries.song(record.songId());
    Instant now = Instant.now();
    boolean current =
        record.active()
            && record.expiresAt().isAfter(now)
            && !record.evidence().isEmpty()
            && queries.basis(record.songId(), now, false).evidence().equals(record.evidence());
    return new Item(
        record.id(),
        record.songId(),
        song.title(),
        song.version(),
        song.isSpecialEvent(),
        song.specialEventLabel(),
        record.status(),
        record.version(),
        record.expiresAt().isAfter(now) ? record.evidence() : List.of(),
        current,
        record.createdAt());
  }

  private void page(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 50) throw AdminCatalogException.invalid();
  }

  private void allowed() {
    if (!policyAllowed)
      throw new AdminCatalogException(503, "SPECIAL_REVIEW_POLICY_DISABLED", "기념일 후보 정책 승인이 필요합니다");
  }

  @Transactional(timeout = 10)
  public Refresh refresh(UUID song) {
    allowed();
    queries.lockSong(song);
    return new Refresh(song, queries.refresh(song, queries.basis(song, Instant.now(), true)));
  }

  @Transactional(timeout = 10)
  public void expire() {
    queries.expire(Instant.now());
  }

  @Transactional(timeout = 10)
  public void scan() {
    if (!queries.scanLock()) return;
    Instant now = Instant.now();
    if (!policyAllowed) return;
    for (UUID song : queries.nextBatch()) {
      queries.lockSong(song);
      queries.refresh(song, queries.basis(song, now, true));
    }
  }

  @Transactional
  public Item change(UUID id, Change input, UUID actor) {
    UUID songId = queries.songId(id);
    queries.lockSong(songId);
    var before = queries.get(id);
    var song = entities.find(ManagedSong.class, songId);
    if (before.version() != input.version() || song.version() != input.songVersion())
      throw AdminCatalogException.conflict();
    if (before.status() != Status.PENDING && input.status() != Status.PENDING)
      throw new AdminCatalogException(
          409, "SPECIAL_REVIEW_ALREADY_DECIDED", "확정·무시한 후보는 먼저 재검토 상태로 변경해 주세요");
    var oldSong = catalog.song(songId);
    if (input.status() == Status.CONFIRMED || input.status() == Status.PENDING) {
      allowed();
      var basis = queries.basis(songId, Instant.now(), true);
      if (basis.evidence().isEmpty())
        throw new AdminCatalogException(
            409, "SPECIAL_REVIEW_BASIS_CHANGED", "현재 날짜 근거를 확인할 수 없습니다");
      if (input.status() == Status.CONFIRMED) {
        if (!basis.evidence().equals(before.evidence())
            || !before.active()
            || !before.expiresAt().isAfter(Instant.now()))
          throw new AdminCatalogException(
              409, "SPECIAL_REVIEW_BASIS_CHANGED", "날짜 근거를 다시 생성하고 확인해 주세요");
        if (input.label() == null || SearchText.normalize(input.label()).isEmpty())
          throw AdminCatalogException.invalid();
        song.isSpecialEvent(true);
        song.specialEventLabel(input.label().strip());
        song.touch();
        entities.flush();
      }
    }
    queries.decide(before, input.status());
    if (input.status() == Status.PENDING)
      queries.refresh(songId, queries.basis(songId, Instant.now(), true));
    // Audit only manual decisions; source-derived dates remain in the expiring review record.
    queriesAudit(before, input.status(), actor, input.reason());
    if (input.status() == Status.CONFIRMED)
      catalog.audit(
          "SONGS", songId, actor, "SPECIAL_EVENT", oldSong, catalog.song(songId), input.reason());
    return item(queries.get(id));
  }

  private void queriesAudit(Record before, Status after, UUID actor, String reason) {
    catalog.audit(
        "SPECIAL_REVIEWS",
        before.id(),
        actor,
        "DECIDE",
        Map.of("status", before.status(), "version", before.version()),
        Map.of("status", after, "version", queries.get(before.id()).version()),
        reason);
  }

  @Transactional(readOnly = true)
  public AuditPage audits(UUID id, int page, int size) {
    page(page, size);
    queries.get(id);
    var rows = catalog.audits("SPECIAL_REVIEWS", id, page, size);
    return new AuditPage(
        rows.subList(0, Math.min(size, rows.size())), page, size, rows.size() > size);
  }
}
