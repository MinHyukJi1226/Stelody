package com.stelody.playlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "playlist", schema = "app")
public class Playlist {
  @Id private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(nullable = false, length = 50)
  private String name;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private Long version;

  protected Playlist() {}

  public Playlist(UUID userId, String name) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.name = name;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    this.updatedAt = createdAt;
  }

  public UUID id() {
    return id;
  }

  public Long version() {
    return version;
  }

  public void rename(String name) {
    this.name = name;
  }

  public void touch() {
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    // A dirty parent is required even when only child items change, or the clock has not advanced.
    updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
  }
}
