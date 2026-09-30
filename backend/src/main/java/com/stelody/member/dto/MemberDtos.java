package com.stelody.member.dto;

import java.util.List;
import java.util.UUID;

public final class MemberDtos {
  private MemberDtos() {}

  public record Counts(long total, long originals, long covers, long collaborations) {}

  public record Card(
      UUID id,
      String name,
      Integer generation,
      String activityStatus,
      String profileImageUrl,
      Counts songCounts) {}

  public record Channel(String name, String youtubeId, String url) {}

  public record Detail(Card member, List<Channel> channels) {}

  public record Page(List<Card> items, String nextCursor, boolean hasNext) {}
}
