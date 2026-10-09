package com.stelody.collection.service;

import com.stelody.collection.dto.CollectionDtos.*;
import com.stelody.collection.repository.CollectionQueries;
import com.stelody.collection.web.CollectionException;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CollectionOperations {
  private final CollectionQueries queries;
  private final boolean retryEnabled;
  private final boolean springSchedule;

  public CollectionOperations(
      CollectionQueries queries,
      @Value("${stelody.collection.retry-enabled:false}") boolean retryEnabled,
      @Value("${stelody.collector.scheduled-enabled:false}") boolean scheduledEnabled,
      @Value("${stelody.collector.enabled:false}") boolean collectorEnabled) {
    this.queries = queries;
    this.retryEnabled = retryEnabled;
    this.springSchedule = scheduledEnabled && collectorEnabled;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Overview overview() {
    var now = Instant.now();
    return new Overview(
        now,
        retryEnabled,
        queries.health("VIDEO", now, springSchedule),
        queries.health("DISCOVERY", now, springSchedule),
        queries.pending(false),
        queries.pending(true),
        queries.active());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page list(String kind, int page, int size, String status) {
    CollectionQueries.table(kind);
    if (page < 0
        || page > 10000
        || size < 1
        || size > 50
        || status != null
            && !List.of("RUNNING", "SUCCEEDED", "FAILED", "QUOTA_EXHAUSTED", "TIMED_OUT")
                .contains(status)) throw CollectionException.invalid();
    var rows = queries.list(kind, page, size, status);
    return new Page(rows.subList(0, Math.min(size, rows.size())), page, size, rows.size() > size);
  }

  @Transactional(readOnly = true)
  public Run detail(String kind, UUID id) {
    return queries.detail(kind, id);
  }

  @Transactional(readOnly = true)
  public Retry retry(UUID id) {
    return queries
        .findRetry(id)
        .orElseThrow(() -> new CollectionException(404, "COLLECTION_RETRY_NOT_FOUND"));
  }

  @Transactional
  public Retry request(String kind, UUID run, UUID actor, RetryInput input) {
    CollectionQueries.table(kind);
    if (input.requestId() == null
        || input.attempt() == null
        || input.attempt() < 1
        || input.reason() == null
        || input.reason().isBlank()
        || input.reason().length() > 500) throw CollectionException.invalid();
    queries.lockRequests();
    var existing = queries.findRetry(input.requestId());
    if (existing.isPresent()) {
      var value = existing.get();
      if (!value.kind().equals(kind)
          || !value.runId().equals(run)
          || value.expectedAttempt() != input.attempt())
        throw new CollectionException(409, "COLLECTION_RETRY_KEY_REUSED");
      return value;
    }
    if (!retryEnabled) throw new CollectionException(503, "COLLECTION_RETRY_DISABLED");
    var target = queries.detail(kind, run);
    if (target.attempt() != input.attempt())
      throw new CollectionException(409, "COLLECTION_RUN_CHANGED");
    if (!List.of("FAILED", "QUOTA_EXHAUSTED", "TIMED_OUT").contains(target.status()))
      throw new CollectionException(409, "COLLECTION_RUN_NOT_RETRYABLE");
    var now = Instant.now();
    var age = kind.equals("VIDEO") ? target.logicalSlot() : target.startedAt();
    if (age.isBefore(now.minusSeconds(172800)) || age.isAfter(now))
      throw new CollectionException(409, "COLLECTION_RETRY_EXPIRED");
    if (queries.active() != null)
      throw new CollectionException(409, "COLLECTION_RETRY_ALREADY_ACTIVE");
    queries.insert(input.requestId(), kind, run, input.attempt(), actor, input.reason().strip());
    return retry(input.requestId());
  }
}
