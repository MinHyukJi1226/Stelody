package com.stelody.playlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "playlist_item", schema = "app")
public class PlaylistItem {
  @Id private UUID id;

  @Column(name = "playlist_id", nullable = false, updatable = false)
  private UUID playlistId;

  @Column(name = "song_id", nullable = false, updatable = false)
  private UUID songId;

  @Column(nullable = false)
  private int position;

  @Column(name = "added_at", nullable = false, updatable = false)
  private Instant addedAt;

  protected PlaylistItem() {}

  public PlaylistItem(UUID playlistId, UUID songId, int position) {
    this.id = UUID.randomUUID();
    this.playlistId = playlistId;
    this.songId = songId;
    this.position = position;
    this.addedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID id() {
    return id;
  }

  public int position() {
    return position;
  }
}
