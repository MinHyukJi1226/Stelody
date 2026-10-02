package com.stelody.admin.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "song_entry", schema = "app")
@DynamicUpdate
public class ManagedSong extends EditedEntity {
  @Column(name = "title")
  private String title;

  @Column(name = "search_title")
  private String searchTitle;

  @Column(name = "song_type")
  private String songType;

  @Column(name = "work_id")
  private UUID workId;

  @Column(name = "visibility")
  private String visibility;

  @Column(name = "representative_video_id")
  private UUID representativeVideoId;

  @Column(name = "is_special_event")
  private boolean isSpecialEvent;

  @Column(name = "special_event_label")
  private String specialEventLabel;

  @Column(name = "search_visibility")
  private String searchVisibility;

  @Column(name = "recommended_search_query")
  private String recommendedSearchQuery;

  @Column(name = "search_checked_at")
  private java.time.Instant searchCheckedAt;

  public String title() {
    return title;
  }

  public void title(String value) {
    title = value;
  }

  public String searchTitle() {
    return searchTitle;
  }

  public void searchTitle(String value) {
    searchTitle = value;
  }

  public String songType() {
    return songType;
  }

  public void songType(String value) {
    songType = value;
  }

  public UUID workId() {
    return workId;
  }

  public void workId(UUID value) {
    workId = value;
  }

  public String visibility() {
    return visibility;
  }

  public void visibility(String value) {
    visibility = value;
  }

  public UUID representativeVideoId() {
    return representativeVideoId;
  }

  public void representativeVideoId(UUID value) {
    representativeVideoId = value;
  }

  public boolean isSpecialEvent() {
    return isSpecialEvent;
  }

  public void isSpecialEvent(boolean value) {
    isSpecialEvent = value;
  }

  public String specialEventLabel() {
    return specialEventLabel;
  }

  public void specialEventLabel(String value) {
    specialEventLabel = value;
  }

  public String searchVisibility() {
    return searchVisibility;
  }

  public void searchVisibility(String value) {
    searchVisibility = value;
  }

  public String recommendedSearchQuery() {
    return recommendedSearchQuery;
  }

  public void recommendedSearchQuery(String value) {
    recommendedSearchQuery = value;
  }

  public java.time.Instant searchCheckedAt() {
    return searchCheckedAt;
  }

  public void searchCheckedAt(java.time.Instant value) {
    searchCheckedAt = value;
  }

  protected ManagedSong() {}

  public ManagedSong(UUID id) {
    this.id = id;
  }
}
