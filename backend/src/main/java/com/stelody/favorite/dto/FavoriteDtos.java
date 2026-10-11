package com.stelody.favorite.dto;

import com.stelody.song.dto.SongDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FavoriteDtos {
  private FavoriteDtos() {}

  @Schema(requiredProperties = {"songId", "favorited"})
  public record State(UUID songId, boolean favorited) {}

  @Schema(requiredProperties = {"songId", "savedAt", "available", "unavailableMessage", "song"})
  public record Item(
      UUID songId,
      @Schema(description = "즐겨찾기 저장 시각입니다. 최근 저장순 정렬의 기준이며 중복 저장 요청으로 바뀌지 않습니다.") Instant savedAt,
      boolean available,
      @Schema(nullable = true) String unavailableMessage,
      @Schema(nullable = true) SongDtos.Card song) {}

  @Schema(requiredProperties = {"items", "nextCursor", "hasNext", "totalCount", "availableCount"})
  public record Page(
      @Schema(description = "savedAt 내림차순, 동률이면 songId 내림차순입니다. 이용 불가 항목도 순서를 유지합니다.")
          List<Item> items,
      @Schema(
              nullable = true,
              description =
                  "다음 페이지에 그대로 전달하는 현재 계정 전용 커서입니다. 마지막 항목의 저장 시각·곡 ID보다 뒤의 항목을 조회하며 다음 페이지가 없으면 null입니다. 커서를 해석하거나 다른 계정에 재사용하지 않습니다.")
          String nextCursor,
      boolean hasNext,
      long totalCount,
      long availableCount) {}
}
