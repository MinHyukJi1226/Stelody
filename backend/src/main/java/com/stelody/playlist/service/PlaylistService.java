package com.stelody.playlist.service;

import com.stelody.catalog.domain.SearchText;
import com.stelody.playlist.domain.Playlist;
import com.stelody.playlist.domain.PlaylistCursor;
import com.stelody.playlist.dto.PlaylistDtos;
import com.stelody.playlist.repository.PlaylistQueries;
import com.stelody.playlist.repository.PlaylistRepository;
import com.stelody.playlist.web.PlaylistException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaylistService {
  private final PlaylistRepository playlists;
  private final PlaylistQueries queries;
  private final PlaylistAccess access;
  private final PlaylistCursor cursors;
  private final int limit;

  public PlaylistService(
      PlaylistRepository playlists,
      PlaylistQueries queries,
      PlaylistAccess access,
      PlaylistCursor cursors,
      @Value("${stelody.playlists.limit:50}") int limit) {
    if (limit < 1) throw new IllegalArgumentException("Playlist limit must be positive");
    this.playlists = playlists;
    this.queries = queries;
    this.access = access;
    this.cursors = cursors;
    this.limit = limit;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PlaylistDtos.Summary create(UUID userId, String name) {
    String validName = name(name);
    queries.lockActiveAccount(userId);
    if (playlists.countByUserId(userId) >= limit)
      throw new PlaylistException(409, "PLAYLIST_LIMIT_REACHED", "플레이리스트 생성 한도에 도달했습니다");
    var playlist = playlists.saveAndFlush(new Playlist(userId, validName));
    return queries.summary(userId, playlist.id());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PlaylistDtos.Page list(UUID userId, int size, String cursor, UUID songId) {
    size(size);
    var rows = queries.list(userId, size, cursors.lists(cursor, userId), songId);
    boolean next = rows.size() > size;
    var page = rows.subList(0, Math.min(size, rows.size()));
    var last = page.isEmpty() ? null : page.getLast();
    return new PlaylistDtos.Page(
        page,
        next ? cursors.lists(userId, last.createdAt(), last.id()) : null,
        next,
        playlists.countByUserId(userId));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PlaylistDtos.Summary detail(UUID userId, UUID playlistId) {
    return queries.summary(userId, playlistId);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PlaylistDtos.Summary rename(UUID userId, UUID playlistId, long version, String name) {
    String validName = name(name);
    var playlist = access.owned(userId, playlistId, version);
    playlist.rename(validName);
    playlist.touch();
    playlists.flush();
    return queries.summary(userId, playlistId);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void delete(UUID userId, UUID playlistId, long version) {
    playlists.delete(access.owned(userId, playlistId, version));
    playlists.flush();
  }

  static void size(int size) {
    if (size < 1 || size > 50) throw PlaylistException.invalid();
  }

  private String name(String raw) {
    if (raw == null || SearchText.normalize(raw).isEmpty()) throw PlaylistException.invalid();
    String name = raw.replaceAll("(?U)^\\s+|\\s+$", "");
    if (name.codePointCount(0, name.length()) > 50) throw PlaylistException.invalid();
    return name;
  }
}
