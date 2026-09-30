package com.stelody.playlist.repository;

import com.stelody.playlist.domain.Playlist;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {
  Optional<Playlist> findByIdAndUserId(UUID id, UUID userId);

  long countByUserId(UUID userId);
}
