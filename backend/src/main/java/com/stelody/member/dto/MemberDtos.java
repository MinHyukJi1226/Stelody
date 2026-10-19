package com.stelody.member.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public final class MemberDtos {
  private MemberDtos() {}

  @Schema(
      requiredProperties = {
        "total",
        "originals",
        "covers",
        "multipleParticipants",
        "collaborations"
      })
  public record Counts(
      long total,
      long originals,
      long covers,
      @Schema(
              description =
                  "이 멤버가 참여한 공개 곡 중 확정 참여자(멤버·외부 아티스트)가 2명 이상인 곡 수입니다. originals·covers와 중복될 수 있으며 게임·IP 협업곡 집계가 아닙니다.")
          long multipleParticipants) {
    @Deprecated
    @JsonProperty("collaborations")
    @Schema(
        deprecated = true,
        description =
            "기존 클라이언트를 위한 다인 참여 곡 수의 호환 필드입니다. multipleParticipants와 항상 같은 값이며 게임·IP 협업곡 수가 아닙니다. 신규 연동은 multipleParticipants를 사용합니다.")
    public long getCollaborations() {
      return multipleParticipants;
    }
  }

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
