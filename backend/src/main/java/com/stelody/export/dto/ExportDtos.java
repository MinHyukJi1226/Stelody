package com.stelody.export.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class ExportDtos {
  private ExportDtos() {}

  @Schema(requiredProperties = {"authorizationUrl", "expiresAt"})
  public record Authorization(String authorizationUrl, Instant expiresAt) {}

  @Schema(requiredProperties = {"status"})
  public record Connection(
      @Schema(
              allowableValues = {
                "DISABLED",
                "DISCONNECTED",
                "CONNECTED",
                "RECONNECT_REQUIRED",
                "REVOKING"
              },
              description =
                  "DISABLED: 기능 비활성이고 저장된 연결 없음, DISCONNECTED: 연결 없음, CONNECTED: 연결됨, RECONNECT_REQUIRED: 추가 동의 필요, REVOKING: 권한 철회 처리 중. 기능 비활성화만으로 기존 연결 상태를 덮어쓰지 않으며 REVOKING 중에는 추가 동의를 시작할 수 없습니다.")
          String status) {}

  public record Create(
      @Schema(
              description =
                  "요청을 식별하는 UUID. 응답을 받지 못한 접수 요청은 같은 목록·version·requestId로 다시 보내면 기존 작업을 반환합니다. 다른 목록이나 버전에 재사용하면 409 EXPORT_REQUEST_REUSED입니다.")
          @NotNull
          UUID requestId,
      @Schema(description = "복사할 원본 플레이리스트의 현재 version") @NotNull @Min(0) Long version) {}

  @Schema(
      requiredProperties = {
        "id",
        "playlistId",
        "version",
        "status",
        "totalCount",
        "copiedCount",
        "skippedCount",
        "remainingCount",
        "youtubePlaylistId",
        "youtubeUrl",
        "errorCode",
        "createdAt",
        "updatedAt"
      })
  public record Job(
      UUID id,
      UUID playlistId,
      @Schema(description = "접수 당시 원본 플레이리스트 version. 내보내기 작업 자체의 버전이 아닙니다.") long version,
      @Schema(
              allowableValues = {
                "QUEUED",
                "RUNNING",
                "SUCCEEDED",
                "FAILED",
                "UNCERTAIN",
                "CANCELLED"
              },
              description =
                  "QUEUED: 대기, RUNNING: 처리 중, SUCCEEDED: 처리 완료(건너뛴 곡 포함 가능), FAILED: 실패, UNCERTAIN: 외부 쓰기 결과 불확실, CANCELLED: 취소. FAILED·UNCERTAIN만 재시도로 QUEUED가 됩니다. 재시도 시 QUEUED·RUNNING·SUCCEEDED는 그대로 반환하고 CANCELLED는 409 EXPORT_NOT_RETRYABLE입니다. 취소 시 SUCCEEDED는 그대로, 그 외는 CANCELLED로 반환합니다. 이미 반영된 외부 목록·곡은 삭제하지 않습니다.")
          String status,
      @Schema(description = "접수 당시 전체 항목 수. copiedCount + skippedCount + remainingCount와 같습니다.")
          int totalCount,
      @Schema(description = "YouTube에 복사된 것으로 확인된 항목 수") int copiedCount,
      @Schema(description = "이용 불가 등의 이유로 건너뛴 항목 수") int skippedCount,
      @Schema(description = "결과가 아직 확정되지 않은 항목 수. UNCERTAIN에서는 외부에 이미 반영된 항목도 포함될 수 있습니다.")
          int remainingCount,
      @Schema(nullable = true) String youtubePlaylistId,
      @Schema(nullable = true) String youtubeUrl,
      @Schema(
              nullable = true,
              description =
                  "작업 실패·불확실 사유. HTTP 요청 오류의 ApiProblem.code와 구분합니다. YOUTUBE_QUOTA_EXCEEDED: 할당량, YOUTUBE_RATE_LIMITED: 호출 제한, YOUTUBE_CHANNEL_REQUIRED: YouTube 채널 필요, YOUTUBE_RECONNECT_REQUIRED·YOUTUBE_CONNECTION_REQUIRED: 연결 필요, REMOTE_PLAYLIST_MISSING: 외부 목록 없음, REMOTE_PLAYLIST_CHANGED: 외부 순서·내용 변경, YOUTUBE_RESULT_UNCONFIRMED·YOUTUBE_RESPONSE_UNCERTAIN: 결과 미확정, YOUTUBE_INVALID_RESPONSE·YOUTUBE_REQUEST_FAILED: 외부 응답 오류, EXPORT_PROCESSING_FAILED: 처리 실패, YOUTUBE_DISCONNECTED: 철회로 취소. 복구 후 같은 작업을 재시도하며 미확정 결과를 성공이나 미반영으로 단정하지 않습니다.")
          String errorCode,
      Instant createdAt,
      Instant updatedAt) {}
}
