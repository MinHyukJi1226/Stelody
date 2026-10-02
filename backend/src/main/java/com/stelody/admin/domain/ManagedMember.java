package com.stelody.admin.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "member", schema = "app")
@DynamicUpdate
public class ManagedMember extends EditedEntity {
  @Column(name = "name")
  private String name;

  @Column(name = "search_name")
  private String searchName;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.SMALLINT)
  @Column(name = "generation")
  private Integer generation;

  @Column(name = "activity_status")
  private String activityStatus;

  @Column(name = "profile_image_url")
  private String profileImageUrl;

  @Column(name = "debut_date")
  private java.time.LocalDate debutDate;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.SMALLINT)
  @Column(name = "birthday_month")
  private Integer birthdayMonth;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.SMALLINT)
  @Column(name = "birthday_day")
  private Integer birthdayDay;

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

  public Integer generation() {
    return generation;
  }

  public void generation(Integer value) {
    generation = value;
  }

  public String activityStatus() {
    return activityStatus;
  }

  public void activityStatus(String value) {
    activityStatus = value;
  }

  public String profileImageUrl() {
    return profileImageUrl;
  }

  public void profileImageUrl(String value) {
    profileImageUrl = value;
  }

  public java.time.LocalDate debutDate() {
    return debutDate;
  }

  public void debutDate(java.time.LocalDate value) {
    debutDate = value;
  }

  public Integer birthdayMonth() {
    return birthdayMonth;
  }

  public void birthdayMonth(Integer value) {
    birthdayMonth = value;
  }

  public Integer birthdayDay() {
    return birthdayDay;
  }

  public void birthdayDay(Integer value) {
    birthdayDay = value;
  }

  protected ManagedMember() {}

  public ManagedMember(UUID id) {
    this.id = id;
  }
}
