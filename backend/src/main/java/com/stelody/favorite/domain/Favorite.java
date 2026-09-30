package com.stelody.favorite.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "favorite", schema = "app")
public class Favorite {
  @Embeddable
  public record Key(
      @Column(name = "user_id", updatable = false) UUID userId,
      @Column(name = "song_id", updatable = false) UUID songId)
      implements Serializable {}

  @EmbeddedId private Key id;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Favorite() {}

  public Favorite(UUID userId, UUID songId, Instant createdAt) {
    this.id = new Key(userId, songId);
    this.createdAt = createdAt;
  }
}
