package com.stelody.playlist.dto;

import com.stelody.song.dto.SongDtos;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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

  public record Order(
      @ArraySchema(
              uniqueItems = true,
              arraySchema =
                  @Schema(
                      description =
                          "원하는 순서의 전체 목록 항목 id입니다. songId와 구분하며 이용 불가 항목도 한 번씩 포함합니다. 누락·중복·다른 목록 항목은 400 INVALID_PLAYLIST_REQUEST입니다. 빈 목록은 []입니다."))
          @NotNull
          List<@NotNull UUID> itemIds,
      @Schema(description = "전체 페이지 조회에 사용한 동일한 목록 version. 저장 성공 시 1 증가합니다.") @NotNull @Min(0)
          Long version) {}

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
      @Schema(description = "플레이리스트 항목 ID. 전체 순서 저장의 itemIds에 사용하며 songId와 구분합니다.") UUID id,
      UUID songId,
      @Schema(description = "0부터 시작하는 목록 순서. 조회는 오름차순이며 이용 불가 항목도 포함합니다.", minimum = "0")
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
      @Schema(description = "이 페이지를 조회한 목록 버전. 전체 순서 편집은 같은 version의 모든 페이지를 모아 저장합니다.")
          long version,
      List<Item> items,
      @Schema(
              nullable = true,
              description =
                  "현재 계정·목록·version에 묶인 다음 페이지 커서. hasNext=false이면 null입니다. 목록 변경 후 재사용하면 409 PLAYLIST_CHANGED이므로 첫 페이지부터 다시 조회합니다.")
          String nextCursor,
      boolean hasNext,
      long totalCount,
      long availableCount) {}

  @Schema(requiredProperties = {"playlist", "itemId"})
  public record Added(Summary playlist, UUID itemId) {}
}
