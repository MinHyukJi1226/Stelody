package com.stelody.admin.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "video", schema = "app")
@DynamicUpdate
public class ManagedVideo extends EditedEntity {
  @Column(name = "video_kind")
  private String videoKind;

  @Column(name = "published_at")
  private java.time.Instant publishedAt;

  @Column(name = "thumbnail_url")
  private String thumbnailUrl;

  public String videoKind() {
    return videoKind;
  }

  public void videoKind(String value) {
    videoKind = value;
  }

  public java.time.Instant publishedAt() {
    return publishedAt;
  }

  public void publishedAt(java.time.Instant value) {
    publishedAt = value;
  }

  public String thumbnailUrl() {
    return thumbnailUrl;
  }

  public void thumbnailUrl(String value) {
    thumbnailUrl = value;
  }

  protected ManagedVideo() {}
}
