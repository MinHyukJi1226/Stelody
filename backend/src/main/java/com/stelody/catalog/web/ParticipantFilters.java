package com.stelody.catalog.web;

import com.stelody.auth.web.ApiFieldError;
import java.util.List;

/** Both names describe participant count; neither classifies game or brand collaborations. */
public final class ParticipantFilters {
  private ParticipantFilters() {}

  public static String resolve(
      String participation, Boolean multipleParticipants, Boolean collaboration) {
    boolean legacy = resolve(multipleParticipants, collaboration);
    String legacyValue = legacy ? "MULTIPLE" : "ALL";
    if (participation == null) return legacyValue;
    if (!List.of("ALL", "SOLO", "MULTIPLE").contains(participation))
      throw CatalogException.invalid(
          List.of(
              new ApiFieldError(
                  "participation", "INVALID_VALUE", "ALL, SOLO, MULTIPLE 중 하나를 지정해 주세요")));
    if ((multipleParticipants != null || collaboration != null)
        && !participation.equals(legacyValue)) {
      String message = "participation과 기존 참여 인원 필터의 의미가 일치해야 합니다";
      var fields = new java.util.ArrayList<ApiFieldError>();
      fields.add(new ApiFieldError("participation", "CONFLICTING_FILTER", message));
      if (multipleParticipants != null)
        fields.add(new ApiFieldError("multipleParticipants", "CONFLICTING_FILTER", message));
      if (collaboration != null)
        fields.add(new ApiFieldError("collaboration", "CONFLICTING_FILTER", message));
      throw CatalogException.invalid(fields);
    }
    return participation;
  }

  public static boolean resolve(Boolean multipleParticipants, Boolean collaboration) {
    if (multipleParticipants != null
        && collaboration != null
        && !multipleParticipants.equals(collaboration)) {
      String message = "multipleParticipants와 이전 collaboration 필터에 서로 다른 값을 지정할 수 없습니다";
      throw CatalogException.invalid(
          List.of(
              new ApiFieldError("multipleParticipants", "CONFLICTING_FILTER", message),
              new ApiFieldError("collaboration", "CONFLICTING_FILTER", message)));
    }
    return multipleParticipants != null ? multipleParticipants : Boolean.TRUE.equals(collaboration);
  }
}
