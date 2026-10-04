package com.stelody.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public final class MemberDtos {
  private MemberDtos() {}

  @Schema(requiredProperties = {"total", "originals", "covers", "collaborations"})
  public record Counts(long total, long originals, long covers, long collaborations) {}

  @Schema(
      requiredProperties = {
        "id",
        "name",
        "generation",
        "activityStatus",
        "profileImageUrl",
        "songCounts"
      })
  public record Card(
      UUID id,
      String name,
      @Schema(nullable = true) Integer generation,
      String activityStatus,
      @Schema(nullable = true) String profileImageUrl,
      Counts songCounts) {}

  @Schema(requiredProperties = {"name", "youtubeId", "url"})
  public record Channel(String name, String youtubeId, String url) {}

  @Schema(requiredProperties = {"member", "channels"})
  public record Detail(Card member, List<Channel> channels) {}

  @Schema(requiredProperties = {"items", "nextCursor", "hasNext"})
  public record Page(
      List<Card> items, @Schema(nullable = true) String nextCursor, boolean hasNext) {}
}
