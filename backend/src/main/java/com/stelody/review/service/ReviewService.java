package com.stelody.review.service;

import com.stelody.review.dto.ReviewDtos;
import com.stelody.review.repository.ReviewQueries;
import com.stelody.review.repository.ReviewRepository;
import com.stelody.review.web.ReviewException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {
  private final ReviewRepository reviews;
  private final ReviewQueries queries;

  public ReviewService(ReviewRepository reviews, ReviewQueries queries) {
    this.reviews = reviews;
    this.queries = queries;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ReviewDtos.Page list(int page, int size, String status, String disposition) {
    if (page < 0
        || page > 10000
        || size < 1
        || size > 50
        || !List.of("PENDING", "IGNORED", "REGISTERED").contains(status)
        || (disposition != null
            && !List.of("REVIEW", "EXCLUDED", "DEFERRED").contains(disposition)))
      throw ReviewException.invalid();
    var rows = queries.list(page, size, status, disposition);
    return new ReviewDtos.Page(
        rows.subList(0, Math.min(size, rows.size())), page, size, rows.size() > size);
  }

  @Transactional(readOnly = true)
  public ReviewDtos.Item detail(UUID id) {
    return queries.detail(id);
  }

  @Transactional
  public ReviewDtos.Item change(UUID id, UUID actor, ReviewDtos.Change change) {
    if (change.version() == null
        || change.version() < 0
        || change.status() == null
        || !List.of("PENDING", "IGNORED").contains(change.status())
        || change.reason() == null
        || change.reason().isBlank()
        || change.reason().length() > 500) throw ReviewException.invalid();
    var item =
        reviews
            .findById(id)
            .orElseThrow(() -> new ReviewException(404, "REVIEW_NOT_FOUND", "검토 후보를 찾을 수 없습니다"));
    if (item.version() != change.version()) throw ReviewException.conflict();
    if ("REGISTERED".equals(item.status()))
      throw new ReviewException(409, "REVIEW_ALREADY_REGISTERED", "등록된 영상은 곡 관리에서 수정해 주세요");
    String before = item.status(), oldNote = item.note(), note = change.reason().strip();
    if (before.equals(change.status()) && note.equals(oldNote)) return queries.detail(id);
    Instant now = Instant.now();
    item.review(change.status(), note, now);
    reviews.flush();
    queries.audit(id, actor, before, change.status(), oldNote, note, now);
    return queries.detail(id);
  }
}
