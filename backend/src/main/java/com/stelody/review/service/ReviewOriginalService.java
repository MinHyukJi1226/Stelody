package com.stelody.review.service;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.service.CatalogManagementService;
import com.stelody.review.domain.OriginalCredits;
import com.stelody.review.domain.OriginalCredits.Evidence;
import com.stelody.review.repository.ReviewOriginalQueries;
import com.stelody.review.youtube.YouTubeOriginalLookup;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewOriginalService {
  public record Candidate(UUID id, String videoId, String channelId, String title) {}

  private static final String REASON = "관리자 선택 등록 원곡 정보 보완";
  private final ReviewOriginalQueries queries;
  private final CatalogManagementService catalog;
  private final YouTubeOriginalLookup lookup;

  public ReviewOriginalService(
      ReviewOriginalQueries queries,
      CatalogManagementService catalog,
      YouTubeOriginalLookup lookup) {
    this.queries = queries;
    this.catalog = catalog;
    this.lookup = lookup;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<UUID, UUID> resolve(List<Candidate> candidates, UUID actor) {
    if (candidates.isEmpty()) return Map.of();
    var targets = new ArrayList<YouTubeOriginalLookup.Target>();
    for (var candidate : candidates) {
      var titles = OriginalCredits.titles(candidate.title());
      var existing = queries.byTitles(titles, List.of());
      var work = existing.size() == 1 ? existing.getFirst() : null;
      targets.add(
          new YouTubeOriginalLookup.Target(
              candidate.id(),
              candidate.videoId(),
              candidate.channelId(),
              titles,
              work == null ? List.of() : queries.names(work),
              work == null ? null : queries.originalId(work).orElse(null)));
    }
    // No database changes precede remote lookup. Full descriptions are kept only in this call.
    var evidence = lookup.find(targets);
    queries.lock();
    var result = new HashMap<UUID, UUID>();
    for (var target : targets) {
      var outcome = evidence.get(target.id());
      if (outcome != null && outcome.ambiguous()) continue;
      var found = outcome == null ? null : outcome.evidence();
      if (found != null) {
        var work = store(found, actor);
        if (work != null) result.put(target.id(), work.id());
      }
    }
    return Map.copyOf(result);
  }

  private Work store(Evidence evidence, UUID actor) {
    String reason = REASON + " (" + evidence.method() + "; " + evidence.sourceUrl() + ")";
    var linked = queries.byVideo(evidence.videoId());
    if (linked.size() > 1) return null;
    var matches =
        !linked.isEmpty()
            ? linked
            : queries.byTitles(List.of(evidence.title()), evidence.artists());
    if (matches.size() > 1) return null;
    var work = matches.isEmpty() ? null : matches.getFirst();
    var artists = work == null ? new ArrayList<UUID>() : new ArrayList<>(work.artistIds());
    if (artists.isEmpty()) {
      var matchedArtists = new LinkedHashMap<String, List<UUID>>();
      for (String name : evidence.artists()) matchedArtists.put(name, queries.artists(name));
      if (matchedArtists.values().stream().anyMatch(ids -> ids.size() > 1)) return null;
      for (String name : evidence.artists()) {
        var existing = matchedArtists.get(name);
        UUID id =
            existing.isEmpty()
                ? catalog
                    .artist(null, new ArtistInput(0L, name, List.of(), reason), actor)
                    .item()
                    .id()
                : existing.getFirst();
        if (!artists.contains(id)) artists.add(id);
      }
    }
    Link link =
        evidence.videoId() == null
            ? null
            : new Link("YOUTUBE", OriginalCredits.url(evidence.videoId()), evidence.sourceUrl());
    if (work != null)
      return catalog.supplementOriginal(work.id(), work.version(), artists, link, actor, reason);
    return catalog
        .work(
            null,
            new WorkInput(
                0L,
                evidence.title(),
                List.of(),
                artists,
                link == null ? List.of() : List.of(link),
                List.of(),
                reason),
            actor)
        .item();
  }
}
