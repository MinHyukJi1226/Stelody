package com.stelody.admin.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "channel", schema = "app")
@DynamicUpdate
public class ManagedChannel extends EditedEntity {
  @Column(name = "youtube_id")
  private String youtubeId;

  @Column(name = "name")
  private String name;

  @Column(name = "member_id")
  private UUID memberId;

  @Column(name = "channel_type")
  private String channelType;

  @Column(name = "collection_enabled")
  private boolean collectionEnabled;

  public String youtubeId() {
    return youtubeId;
  }

  public void youtubeId(String value) {
    youtubeId = value;
  }

  public String name() {
    return name;
  }

  public void name(String value) {
    name = value;
  }

  public UUID memberId() {
    return memberId;
  }

  public void memberId(UUID value) {
    memberId = value;
  }

  public String channelType() {
    return channelType;
  }

  public void channelType(String value) {
    channelType = value;
  }

  public boolean collectionEnabled() {
    return collectionEnabled;
  }

  public void collectionEnabled(boolean value) {
    collectionEnabled = value;
  }

  protected ManagedChannel() {}

  public ManagedChannel(UUID id) {
    this.id = id;
  }
}
