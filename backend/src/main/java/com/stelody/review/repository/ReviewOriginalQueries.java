package com.stelody.review.repository;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.repository.CatalogAdminQueries;
import com.stelody.catalog.domain.SearchText;
import com.stelody.review.domain.OriginalCredits;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewOriginalQueries {
  private final JdbcClient jdbc;
  private final CatalogAdminQueries catalog;

  public ReviewOriginalQueries(JdbcClient jdbc, CatalogAdminQueries catalog) {
    this.jdbc = jdbc;
    this.catalog = catalog;
  }

  public void lock() {
    // Serialize original/artist creation across batches, after candidate and channel locks.
    jdbc.sql("SELECT pg_advisory_xact_lock(8127270025)").query().singleRow();
  }

  public List<Work> byVideo(String videoId) {
    if (videoId == null) return List.of();
    var links =
        jdbc.sql(
                "SELECT work_id,url FROM app.external_link WHERE work_id IS NOT NULL AND url LIKE :fragment ESCAPE '!' ORDER BY id LIMIT 101")
            .param("fragment", "%" + videoId.replace("_", "!_") + "%")
            .query((r, n) -> Map.entry(r.getObject("work_id", UUID.class), r.getString("url")))
            .list();
    var ids =
        links.stream()
            .filter(
                e -> OriginalCredits.youtubeId(e.getValue()).filter(videoId::equals).isPresent())
            .map(Map.Entry::getKey)
            .distinct()
            .toList();
    return ids.stream().limit(2).map(catalog::work).toList();
  }

  public List<Work> byTitles(List<String> titles, List<String> artists) {
    if (titles.isEmpty()) return List.of();
    var keys = titles.stream().map(SearchText::searchKey).distinct().toList();
    var ids =
        jdbc.sql(
                """
        SELECT w.id FROM app.musical_work w
        WHERE replace(w.search_title,' ','') IN (:keys)
          OR EXISTS(SELECT 1 FROM app.work_alias a WHERE a.work_id=w.id AND replace(a.search_alias,' ','') IN (:keys))
        ORDER BY w.id LIMIT 26
        """)
            .param("keys", keys)
            .query(UUID.class)
            .list();
    if (ids.size() > 25) return ids.stream().limit(2).map(catalog::work).toList();
    return ids.stream()
        .map(catalog::work)
        .filter(
            w ->
                artists.isEmpty()
                    || w.artistIds().isEmpty()
                    || artists.stream()
                        .allMatch(a -> artistKeys(w).contains(SearchText.searchKey(a))))
        .toList();
  }

  public List<String> names(Work work) {
    return work.artistIds().stream()
        .map(catalog::artist)
        .flatMap(
            a ->
                java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(a.name()), a.aliases().stream()))
        .distinct()
        .toList();
  }

  private Set<String> artistKeys(Work work) {
    var keys = new HashSet<String>();
    for (UUID id : work.artistIds()) {
      var artist = catalog.artist(id);
      keys.add(SearchText.searchKey(artist.name()));
      artist.aliases().stream().map(SearchText::searchKey).forEach(keys::add);
    }
    return keys;
  }

  public List<UUID> artists(String name) {
    return jdbc.sql(
            """
        SELECT a.id FROM app.artist a WHERE replace(a.search_name,' ','')=:key
          OR EXISTS(SELECT 1 FROM app.artist_alias x WHERE x.artist_id=a.id AND replace(x.search_alias,' ','')=:key)
        ORDER BY a.id LIMIT 2
        """)
        .param("key", SearchText.searchKey(name))
        .query(UUID.class)
        .list();
  }

  public Optional<String> originalId(Work work) {
    var ids =
        work.links().stream()
            .map(LinkView::url)
            .map(OriginalCredits::youtubeId)
            .flatMap(Optional::stream)
            .distinct()
            .toList();
    return ids.size() == 1 ? Optional.of(ids.getFirst()) : Optional.empty();
  }
}
