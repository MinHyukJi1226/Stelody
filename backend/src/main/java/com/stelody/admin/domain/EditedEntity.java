package com.stelody.admin.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class EditedEntity {
  @Id protected UUID id;
  @Version protected long version;

  @Column(name = "edited_at")
  protected Instant editedAt;

  public UUID id() {
    return id;
  }

  public long version() {
    return version;
  }

  public void touch() {
    editedAt = Instant.now();
  }
}
