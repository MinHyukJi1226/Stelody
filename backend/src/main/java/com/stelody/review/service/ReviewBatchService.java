package com.stelody.review.service;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.repository.CatalogAdminQueries;
import com.stelody.admin.service.CatalogManagementService;
import com.stelody.admin.web.AdminCatalogException;
import com.stelody.auth.web.ApiFieldError;
import com.stelody.collector.repository.CollectionRuleStore;
import com.stelody.review.domain.ReviewRegistrationRules;
import com.stelody.review.dto.ReviewDtos;
import com.stelody.review.repository.ReviewQueries;
import com.stelody.review.web.ReviewException;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReviewBatchService {
  private final ReviewQueries reviews;
  private final CatalogManagementService catalog;
  private final CatalogAdminQueries queries;
  private final ObjectMapper mapper;
  private final CollectionRuleStore classification;
  private final ReviewOriginalService originals;
  private final ReviewRegistrationRules registration = new ReviewRegistrationRules();

  public ReviewBatchService(
      ReviewQueries reviews,
      CatalogManagementService catalog,
      CatalogAdminQueries queries,
      ObjectMapper mapper,
      CollectionRuleStore classification,
      ReviewOriginalService originals) {
    this.reviews = reviews;
    this.catalog = catalog;
    this.queries = queries;
    this.mapper = mapper;
    this.classification = classification;
    this.originals = originals;
  }

  @Transactional
  public ReviewDtos.CompletionResult complete(UUID actor, ReviewDtos.Complete body) {
    var snapshot =
        body.items().stream()
            .collect(
                Collectors.toMap(
                    ReviewDtos.Candidate::id,
                    Function.identity(),
                    (a, b) -> {
                      throw ReviewException.invalid();
                    }));
    var selected = body.selected().stream().map(ReviewDtos.Selection::id).toList();
    if (selected.isEmpty()
        || selected.stream().distinct().count() != selected.size()
        || !snapshot.keySet().containsAll(selected)) throw ReviewException.invalid();
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    if (reviews.requestChanged(body.requestId(), hash)) throw ReviewException.conflict();
    var locked =
        reviews.lockBatch(body.items()).stream()
            .collect(Collectors.toMap(ReviewQueries.BatchRow::id, Function.identity()));
    boolean replay =
        selected.stream()
            .allMatch(
                id -> {
                  var r = locked.get(id);
                  return r != null
                      && "REGISTERED".equals(r.status())
                      && body.requestId().equals(r.batch())
                      && hash.equals(r.hash())
                      && r.songId() != null;
                });
    if (replay)
      return new ReviewDtos.CompletionResult(
          selected.stream()
              .map(id -> new ReviewDtos.RegisteredSong(id, locked.get(id).songId()))
              .toList(),
          0,
          true);
    for (var candidate : body.items()) {
      var row = locked.get(candidate.id());
      if (row == null) {
        if (selected.contains(candidate.id())) throw ReviewException.conflict();
        else continue;
      }
      if ("REGISTERED".equals(row.status()) && !selected.contains(row.id())) continue;
      if (row.version() != candidate.version()
          || !"PENDING".equals(row.status())
          || !"REVIEW".equals(row.disposition())) throw ReviewException.conflict();
    }
    var sources = body.selected().stream().map(s -> reviews.detail(s.id())).toList();
    // Candidate locks precede channel locks; stable ordering avoids crossed batch lock orders.
    sources.stream()
        .map(ReviewDtos.Item::channelId)
        .distinct()
        .sorted()
        .forEach(queries::lockChannel);
    var members = reviews.registrationMembers();
    var configuration = classification.current();
    var plans = new ArrayList<ReviewRegistrationRules.Plan>();
    for (int i = 0; i < sources.size(); i++) {
      var source = sources.get(i);
      try {
        plans.add(
            registration.plan(source, queries.channel(source.channelId()), members, configuration));
      } catch (ReviewRegistrationRules.Unresolved error) {
        throw unresolved(i, error);
      }
    }
    var covers = new ArrayList<ReviewOriginalService.Candidate>();
    for (int i = 0; i < plans.size(); i++) {
      var source = sources.get(i);
      if (plans.get(i).song().type() == SongType.COVER)
        covers.add(
            new ReviewOriginalService.Candidate(
                source.id(),
                source.youtubeId(),
                queries.channel(source.channelId()).youtubeId(),
                plans.get(i).song().title()));
    }
    var workIds = originals.resolve(covers, actor);
    var registered = new ArrayList<ReviewDtos.RegisteredSong>();
    for (int i = 0; i < body.selected().size(); i++) {
      var selection = body.selected().get(i);
      try {
        var plan = plans.get(i);
        var input = copy(plan.song(), 0, Visibility.DRAFT, null, workIds.get(selection.id()));
        var draft = catalog.song(null, input, actor).item();
        var video =
            catalog.register(
                selection.id(),
                new Registration(
                    snapshot.get(selection.id()).version(),
                    draft.id(),
                    draft.version(),
                    plan.kind(),
                    ReviewRegistrationRules.REASON),
                actor);
        var attached = queries.song(draft.id());
        catalog.song(
            draft.id(),
            copy(input, attached.version(), Visibility.DRAFT, video.id(), input.workId()),
            actor);
        reviews.rememberBatch(selection.id(), body.requestId(), hash);
        registered.add(new ReviewDtos.RegisteredSong(selection.id(), draft.id()));
      } catch (AdminCatalogException error) {
        throw new ReviewException(
            error.status(),
            error.code(),
            "선택한 " + (i + 1) + "번째 후보: " + error.getMessage(),
            List.of(
                new ApiFieldError(
                    "selected[" + i + "].id", error.code(), "후보의 수집 정보와 등록 상태를 확인해 주세요")));
      }
    }
    int deleted = reviews.complete(body, hash, actor);
    if (deleted == -1) throw ReviewException.conflict();
    if (deleted == -2) throw new ReviewException(403, "ACCESS_DENIED", "관리자 권한을 확인해 주세요");
    queries.audit(
        "REVIEWS",
        body.requestId(),
        actor,
        "BATCH_REGISTER",
        Map.of("candidateCount", body.items().size(), "selected", selected),
        Map.of(
            "registeredSongs",
            registered,
            "deletedCount",
            deleted,
            "classificationRuleVersion",
            configuration.version(),
            "visibility",
            "DRAFT"),
        ReviewRegistrationRules.REASON);
    return new ReviewDtos.CompletionResult(registered, deleted, false);
  }

  private ReviewException unresolved(int index, ReviewRegistrationRules.Unresolved error) {
    return new ReviewException(
        409,
        "CANDIDATE_CLASSIFICATION_REQUIRED",
        "선택한 " + (index + 1) + "번째 후보의 등록 정보를 확정할 수 없습니다",
        List.of(
            new ApiFieldError(
                "selected[" + index + "].id", error.reason(), "후보의 곡명·곡 유형을 확인해 주세요")));
  }

  private SongInput copy(
      SongInput s, long version, Visibility visibility, UUID representative, UUID workId) {
    return new SongInput(
        version,
        s.title(),
        s.type(),
        workId,
        visibility,
        representative,
        s.aliases(),
        s.memberIds(),
        s.externalArtistIds(),
        s.isSpecialEvent(),
        s.specialEventLabel(),
        s.searchVisibility(),
        s.recommendedSearchQuery(),
        s.links(),
        s.karaoke(),
        s.reason());
  }
}
