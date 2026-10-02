package com.stelody.admin.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "musical_work", schema = "app")
@DynamicUpdate
public class ManagedWork extends EditedEntity {
  @Column(name = "title")
  private String title;

  @Column(name = "search_title")
  private String searchTitle;

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

  protected ManagedWork() {}

  public ManagedWork(UUID id) {
    this.id = id;
  }
}
