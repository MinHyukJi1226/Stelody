package com.stelody.playlist.repository;

import com.stelody.playlist.domain.PlaylistItem;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaylistItemRepository extends JpaRepository<PlaylistItem, UUID> {
  Optional<PlaylistItem> findByIdAndPlaylistId(UUID id, UUID playlistId);

  boolean existsByPlaylistIdAndSongId(UUID playlistId, UUID songId);

  long countByPlaylistId(UUID playlistId);
}
