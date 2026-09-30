package com.stelody.playlist.service;

import com.stelody.playlist.domain.PlaylistCursor;
import com.stelody.playlist.domain.PlaylistItem;
import com.stelody.playlist.dto.PlaylistDtos;
import com.stelody.playlist.repository.PlaylistItemRepository;
import com.stelody.playlist.repository.PlaylistQueries;
import com.stelody.playlist.web.PlaylistException;
import com.stelody.song.dto.SongDtos;
import com.stelody.song.repository.SongRepository;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaylistItemService {
  private final PlaylistItemRepository items;
  private final PlaylistQueries queries;
  private final PlaylistAccess access;
  private final PlaylistCursor cursors;
  private final SongRepository songs;
  private final int limit;

  public PlaylistItemService(
      PlaylistItemRepository items,
      PlaylistQueries queries,
      PlaylistAccess access,
      PlaylistCursor cursors,
      SongRepository songs,
      @Value("${stelody.playlists.item-limit:500}") int limit) {
    if (limit < 1) throw new IllegalArgumentException("Playlist item limit must be positive");
    this.items = items;
    this.queries = queries;
    this.access = access;
    this.cursors = cursors;
    this.songs = songs;
    this.limit = limit;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PlaylistDtos.Items list(UUID userId, UUID playlistId, int size, String cursor) {
    PlaylistService.size(size);
    var summary = queries.summary(userId, playlistId);
    var position = cursors.items(cursor, userId, playlistId, summary.version());
    var rows = queries.items(userId, playlistId, size, position);
    boolean next = rows.size() > size;
    var page = rows.subList(0, Math.min(size, rows.size()));
    var ids = page.stream().map(PlaylistQueries.ItemRow::songId).toList();
    var cards =
        songs.cards(songs.findAll(ids, songs.publication())).stream()
            .collect(Collectors.toMap(SongDtos.Card::id, Function.identity()));
    var entries =
        page.stream()
            .map(
                row -> {
                  var card = cards.get(row.songId());
                  return new PlaylistDtos.Item(
                      row.id(),
                      row.songId(),
                      row.position(),
                      row.addedAt(),
                      card != null,
                      card == null ? "현재 이용할 수 없는 곡" : null,
                      card);
                })
            .toList();
    var last = page.isEmpty() ? null : page.getLast();
    return new PlaylistDtos.Items(
        summary.version(),
        entries,
        next ? cursors.items(userId, playlistId, summary.version(), last.position()) : null,
        next,
        summary.totalCount(),
        summary.availableCount());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PlaylistDtos.Added add(UUID userId, UUID playlistId, long version, UUID songId) {
    if (songId == null) throw PlaylistException.invalid();
    access.claim(userId, playlistId, version);
    if (items.existsByPlaylistIdAndSongId(playlistId, songId))
      throw new PlaylistException(409, "PLAYLIST_SONG_ALREADY_EXISTS", "이미 추가한 곡입니다");
    if (!queries.publiclyAvailable(songId))
      throw new PlaylistException(404, "CATALOG_NOT_FOUND", "공개된 곡을 찾을 수 없습니다");
    long count = items.countByPlaylistId(playlistId);
    if (count >= limit)
      throw new PlaylistException(409, "PLAYLIST_ITEM_LIMIT_REACHED", "플레이리스트 곡 한도에 도달했습니다");
    var item = items.saveAndFlush(new PlaylistItem(playlistId, songId, Math.toIntExact(count)));
    return new PlaylistDtos.Added(queries.summary(userId, playlistId), item.id());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PlaylistDtos.Summary remove(UUID userId, UUID playlistId, long version, UUID itemId) {
    access.claim(userId, playlistId, version);
    var item =
        items.findByIdAndPlaylistId(itemId, playlistId).orElseThrow(PlaylistException::missing);
    items.delete(item);
    items.flush();
    queries.closeGap(userId, playlistId, item.position());
    return queries.summary(userId, playlistId);
  }
}
