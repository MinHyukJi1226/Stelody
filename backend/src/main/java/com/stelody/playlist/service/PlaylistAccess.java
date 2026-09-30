package com.stelody.playlist.service;

import com.stelody.playlist.domain.Playlist;
import com.stelody.playlist.repository.PlaylistRepository;
import com.stelody.playlist.web.PlaylistException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Called inside the mutation service's transaction; ownership is checked before version or items.
 */
@Component
public class PlaylistAccess {
  private final PlaylistRepository playlists;

  public PlaylistAccess(PlaylistRepository playlists) {
    this.playlists = playlists;
  }

  public Playlist owned(UUID userId, UUID playlistId, long version) {
    if (version < 0) throw PlaylistException.invalid();
    var playlist =
        playlists.findByIdAndUserId(playlistId, userId).orElseThrow(PlaylistException::missing);
    if (playlist.version() != version) throw PlaylistException.changed();
    return playlist;
  }
}
