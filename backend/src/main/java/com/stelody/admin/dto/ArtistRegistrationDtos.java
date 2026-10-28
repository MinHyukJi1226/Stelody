package com.stelody.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public final class ArtistRegistrationDtos {
  private ArtistRegistrationDtos() {}

  @Schema(
      description =
          "NOT_PROCESSED: 본인의 접수 기록 없음, PROCESSING: 처리 중, COMPLETED: 등록 완료, FAILED: 등록 실패. 조회 오류와 미처리는 별개입니다.")
  public enum Status {
    NOT_PROCESSED,
    PROCESSING,
    COMPLETED,
    FAILED
  }

  @Schema(
      requiredProperties = {
        "requestId",
        "status",
        "artistId",
        "errorCode",
        "createdAt",
        "expiresAt",
        "finishedAt",
        "pollAfterSeconds"
      })
  public record Result(
      UUID requestId,
      Status status,
      @Schema(nullable = true, description = "COMPLETED일 때 생성된 아티스트 UUID. 이름 검색으로 추정하지 않습니다.")
          UUID artistId,
      @Schema(nullable = true, description = "FAILED일 때 정규화한 업무 오류 코드. 내부 오류·입력값은 포함하지 않습니다.")
          String errorCode,
      @Schema(nullable = true) Instant createdAt,
      @Schema(nullable = true, description = "최초 접수 후 24시간. 재조회·재전송으로 연장되지 않습니다.")
          Instant expiresAt,
      @Schema(nullable = true) Instant finishedAt,
      @Schema(nullable = true, description = "PROCESSING일 때 3초 간격으로 조회. 그 외에는 null.")
          Integer pollAfterSeconds) {}
}
