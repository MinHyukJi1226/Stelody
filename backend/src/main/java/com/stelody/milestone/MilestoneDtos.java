package com.stelody.milestone;

import com.stelody.song.dto.SongDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public final class MilestoneDtos {
  private MilestoneDtos() {}

  public enum Status {
    DISABLED,
    PENDING,
    READY
  }

  @Schema(requiredProperties = {"song", "target", "viewCount", "observedAt"})
  public record Approaching(SongDtos.Card song, long target, long viewCount, Instant observedAt) {}

  @Schema(requiredProperties = {"song", "target", "detectedAt"})
  public record Achievement(
      SongDtos.Card song,
      long target,
      @Schema(description = "서버가 목표 돌파를 처음 확인한 시각입니다. 실제 유튜브 달성 시각이 아닙니다.") Instant detectedAt) {}

  @Schema(requiredProperties = {"status", "checkedAt", "approaching", "achievements"})
  public record Feed(
      Status status,
      Instant checkedAt,
      List<Approaching> approaching,
      List<Achievement> achievements) {}
}
