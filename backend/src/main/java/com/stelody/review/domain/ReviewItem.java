package com.stelody.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "review_item", schema = "app")
@DynamicUpdate
public class ReviewItem {
  @Id private UUID id;

  @Column(name = "review_status", length = 16, nullable = false)
  private String status;

  @Column(name = "review_note", length = 500)
  private String note;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Version private long version;

  protected ReviewItem() {}

  public String status() {
    return status;
  }

  public String note() {
    return note;
  }

  public long version() {
    return version;
  }

  public void review(String status, String note, Instant now) {
    this.status = status;
    this.note = note;
    reviewedAt = now;
  }
}
