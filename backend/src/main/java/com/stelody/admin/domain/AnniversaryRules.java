package com.stelody.admin.domain;

import java.time.*;
import java.util.*;

/** Date matches are evidence for review, never a final event category or label. */
public final class AnniversaryRules {
  private AnniversaryRules() {}

  public record Participant(
      UUID id, String name, Integer birthdayMonth, Integer birthdayDay, LocalDate debutDate) {}

  public record Evidence(
      UUID memberId,
      String memberName,
      String reason,
      LocalDate basisDate,
      LocalDate publishedDate,
      UUID videoId) {}

  public static List<Evidence> match(
      Instant published, UUID video, List<Participant> participants) {
    if (published == null) return List.of();
    LocalDate date = published.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
    var result = new ArrayList<Evidence>();
    for (var member : participants) {
      if (member.birthdayMonth() != null
          && member.birthdayDay() != null
          && date.getMonthValue() == member.birthdayMonth()
          && date.getDayOfMonth() == member.birthdayDay())
        result.add(
            new Evidence(member.id(), member.name(), "BIRTHDAY_DATE_MATCH", date, date, video));
      if (member.debutDate() != null
          && date.isAfter(member.debutDate())
          && date.getMonthValue() == member.debutDate().getMonthValue()
          && date.getDayOfMonth() == member.debutDate().getDayOfMonth())
        result.add(
            new Evidence(
                member.id(), member.name(), "DEBUT_DATE_MATCH", member.debutDate(), date, video));
    }
    return List.copyOf(result);
  }
}
