package com.stelody.catalog.web;

import com.stelody.auth.web.ApiFieldError;
import java.util.List;

/** Both names describe participant count; neither classifies game or brand collaborations. */
public final class ParticipantFilters {
  private ParticipantFilters() {}

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
