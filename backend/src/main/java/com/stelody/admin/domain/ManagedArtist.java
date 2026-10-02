package com.stelody.admin.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "artist", schema = "app")
@DynamicUpdate
public class ManagedArtist extends EditedEntity {
  @Column(name = "name")
  private String name;

  @Column(name = "search_name")
  private String searchName;

  public String name() {
    return name;
  }

  public void name(String value) {
    name = value;
  }

  public String searchName() {
    return searchName;
  }

  public void searchName(String value) {
    searchName = value;
  }

  protected ManagedArtist() {}

  public ManagedArtist(UUID id) {
    this.id = id;
  }
}
