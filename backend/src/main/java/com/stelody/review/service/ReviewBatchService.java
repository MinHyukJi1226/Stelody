package com.stelody.review.service;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.repository.CatalogAdminQueries;
import com.stelody.admin.service.CatalogManagementService;
import com.stelody.admin.web.AdminCatalogException;
import com.stelody.auth.web.ApiFieldError;
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

  public ReviewBatchService(
      ReviewQueries reviews,
      CatalogManagementService catalog,
      CatalogAdminQueries queries,
      ObjectMapper mapper) {
    this.reviews = reviews;
    this.catalog = catalog;
    this.queries = queries;
    this.mapper = mapper;
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
    var registered = new ArrayList<ReviewDtos.RegisteredSong>();
    for (int i = 0; i < body.selected().size(); i++) {
      var selection = body.selected().get(i);
      var input = selection.song();
      if (input.version() != 0 || input.representativeVideoId() != null)
        throw ReviewException.invalid();
      try {
        var draft = catalog.song(null, copy(input, 0, Visibility.DRAFT, null), actor).item();
        var video =
            catalog.register(
                selection.id(),
                new Registration(
                    snapshot.get(selection.id()).version(),
                    draft.id(),
                    draft.version(),
                    selection.kind(),
                    input.reason()),
                actor);
        var attached = queries.song(draft.id());
        catalog.song(
            draft.id(), copy(input, attached.version(), input.visibility(), video.id()), actor);
        reviews.rememberBatch(selection.id(), body.requestId(), hash);
        registered.add(new ReviewDtos.RegisteredSong(selection.id(), draft.id()));
      } catch (AdminCatalogException error) {
        int index = i;
        throw new ReviewException(
            error.status(),
            error.code(),
            "선택한 " + (i + 1) + "번째 곡: " + error.getMessage(),
            error.fieldErrors().stream()
                .map(
                    e ->
                        new ApiFieldError(
                            "selected[" + index + "].song." + e.field(), e.code(), e.message()))
                .toList());
      }
    }
    int deleted = reviews.complete(body, hash, actor);
    if (deleted == -1) throw ReviewException.conflict();
    if (deleted == -2) throw new ReviewException(403, "ACCESS_DENIED", "관리자 권한을 확인해 주세요");
    return new ReviewDtos.CompletionResult(registered, deleted, false);
  }

  private SongInput copy(SongInput s, long version, Visibility visibility, UUID representative) {
    return new SongInput(
        version,
        s.title(),
        s.type(),
        s.workId(),
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
