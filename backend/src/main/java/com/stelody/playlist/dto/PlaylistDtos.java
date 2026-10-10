package com.stelody.playlist.dto;

import com.stelody.song.dto.SongDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlaylistDtos {
  private PlaylistDtos() {}

  public record Create(@NotBlank String name) {}

  public record Rename(@NotBlank String name, @NotNull @Min(0) Long version) {}

  public record Add(@NotNull UUID songId, @NotNull @Min(0) Long version) {}

  public record Order(@NotNull List<@NotNull UUID> itemIds, @NotNull @Min(0) Long version) {}

  @Schema(
      requiredProperties = {
        "id",
        "name",
        "version",
        "createdAt",
        "updatedAt",
        "totalCount",
        "availableCount",
        "containsSong"
      })
  public record Summary(
      UUID id,
      String name,
      long version,
      Instant createdAt,
      Instant updatedAt,
      long totalCount,
      long availableCount,
      @Schema(
              nullable = true,
              description =
                  "목록 조회에서 지정한 songId의 저장 여부. 이용 불가 곡도 포함합니다. "
                      + "songId 생략 및 생성·상세·변경 응답에서는 null입니다. 조회 시점의 값이며 추가 시 중복 검증은 별도로 수행합니다.")
          Boolean containsSong) {}

  @Schema(requiredProperties = {"items", "nextCursor", "hasNext", "totalCount"})
  public record Page(
      List<Summary> items,
      @Schema(nullable = true) String nextCursor,
      boolean hasNext,
      long totalCount) {}

  @Schema(
      requiredProperties = {
        "id",
        "songId",
        "position",
        "addedAt",
        "available",
        "unavailableMessage",
        "song"
      })
  public record Item(
      UUID id,
      UUID songId,
      int position,
      Instant addedAt,
      boolean available,
      @Schema(nullable = true) String unavailableMessage,
      @Schema(nullable = true) SongDtos.Card song) {}

  @Schema(
      requiredProperties = {
        "version",
        "items",
        "nextCursor",
        "hasNext",
        "totalCount",
        "availableCount"
      })
  public record Items(
      long version,
      List<Item> items,
      @Schema(nullable = true) String nextCursor,
      boolean hasNext,
      long totalCount,
      long availableCount) {}

  @Schema(requiredProperties = {"playlist", "itemId"})
  public record Added(Summary playlist, UUID itemId) {}
}
