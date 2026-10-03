package com.stelody.song.service;

import com.stelody.catalog.web.CatalogException;
import com.stelody.song.domain.SongCursor;
import com.stelody.song.domain.SongQuery;
import com.stelody.song.domain.SongRecommendations;
import com.stelody.song.dto.SongDtos;
import com.stelody.song.repository.SongRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SongService {
  private final SongRepository songs;
  private final SongCursor cursors;

  public SongService(SongRepository songs, SongCursor cursors) {
    this.songs = songs;
    this.cursors = cursors;
  }

  public SongDtos.Page list(SongQuery query, String cursor) {
    var publication = songs.publication();
    var position = cursors.decode(cursor, query, publication);
    if (query.emptySearch()) return new SongDtos.Page(List.of(), null, false);
    var rows = songs.list(query, publication, position);
    boolean more = rows.size() > query.size();
    var page = more ? rows.subList(0, query.size()) : rows;
    String next = null;
    if (more) {
      var last = page.getLast();
      next =
          cursors.encode(
              query, publication, last.rank(), last.viewCount(), last.publishedAt(), last.id());
    }
    return new SongDtos.Page(songs.cards(page), next, more);
  }

  public SongDtos.Detail detail(UUID id) {
    var publication = songs.publication();
    var row = songs.find(id, publication).orElseThrow(CatalogException::missing);
    return new SongDtos.Detail(
        songs.cards(List.of(row)).getFirst(),
        new SongDtos.Video(
            row.videoId(),
            row.youtubeId(),
            row.videoKind(),
            "https://www.youtube.com/watch?v=" + row.youtubeId(),
            row.embeddable()),
        new SongDtos.SearchHelp(row.searchVisibility(), row.recommendedQuery(), row.checkedAt()),
        songs.cards(songs.related(row, publication)),
        songs.links(id, null),
        songs.karaoke(id, null),
        row.workId() == null
            ? null
            : new SongDtos.WorkResources(
                row.workId(), songs.links(null, row.workId()), songs.karaoke(null, row.workId())));
  }

  public SongDtos.Recommendations recommendations(int size) {
    if (size < 1 || size > 20) throw CatalogException.invalid();
    var ids = SongRecommendations.select(songs.recommendationCandidates(), size);
    if (ids.isEmpty()) return new SongDtos.Recommendations(List.of());
    var rows =
        songs.findAll(ids, songs.publication()).stream()
            .collect(Collectors.toMap(SongRepository.Row::id, Function.identity()));
    return new SongDtos.Recommendations(songs.cards(ids.stream().map(rows::get).toList()));
  }
}
