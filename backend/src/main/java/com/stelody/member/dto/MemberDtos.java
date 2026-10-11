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
        "unitName",
        "chzzkUrl",
        "xUrl",
        "activityStatus",
        "profileImageUrl",
        "songCounts"
      })
  public record Card(
      UUID id,
      String name,
      @Schema(nullable = true) Integer generation,
      @Schema(description = "기수와 별도로 관리하는 유닛명. 미등록이면 null.", nullable = true) String unitName,
      @Schema(description = "치지직 HTTPS 링크. 미등록이면 null.", nullable = true) String chzzkUrl,
      @Schema(description = "X HTTPS 링크. 미등록이면 null.", nullable = true) String xUrl,
      @Schema(
              allowableValues = {"ACTIVE", "GRADUATED"},
              description = "ACTIVE: 활동 중, GRADUATED: 졸업. 졸업 멤버의 공개 곡도 조회할 수 있습니다.")
          String activityStatus,
      @Schema(nullable = true) String profileImageUrl,
      Counts songCounts) {}

  @Schema(requiredProperties = {"name", "youtubeId", "url"})
  public record Channel(String name, String youtubeId, String url) {}

  @Schema(requiredProperties = {"member", "channels"})
  public record Detail(
      Card member,
      @Schema(description = "YouTube 채널 목록. 등록된 채널이 없으면 빈 배열이며 null은 반환하지 않습니다.")
          List<Channel> channels) {}

  @Schema(requiredProperties = {"items", "nextCursor", "hasNext"})
  public record Page(
      List<Card> items, @Schema(nullable = true) String nextCursor, boolean hasNext) {}
}
