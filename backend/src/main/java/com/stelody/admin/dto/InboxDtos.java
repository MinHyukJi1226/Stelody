package com.stelody.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public final class InboxDtos {
  private InboxDtos() {}

  @Schema(
      requiredProperties = {
        "checkedAt",
        "failureWindowStart",
        "newReviewCount",
        "incompleteAutoRegistrationCount",
        "specialEventReviewCount",
        "collectionFailureCount"
      })
  public record Overview(
      @Schema(description = "집계 시점의 DB 시각. 모든 건수는 같은 DB 스냅샷에서 조회합니다.") Instant checkedAt,
      @Schema(description = "수집 실패 집계 시작 시각. checkedAt의 24시간 전이며 양 끝 시각을 포함합니다.")
          Instant failureWindowStart,
      @Schema(
              minimum = "0",
              description =
                  "GET /api/v1/admin/reviews?status=PENDING&disposition=REVIEW 전체 후보 수. 30일이 지난 출처는 DEFERRED로 간주해 제외합니다. 무시·등록 처리하면 제외합니다.")
          long newReviewCount,
      @Schema(
              minimum = "0",
              description =
                  "GET /api/v1/admin/cover-auto-publication/registrations?incompleteOnly=true 전체 건수. 자동 등록 곡만 대상이며 원곡·원곡 아티스트·검색 확인·TJ·KY 확인 중 누락이 있으면 포함합니다. 별칭은 선택이며 숨김도 포함하고 보완 완료 시 제외합니다.")
          long incompleteAutoRegistrationCount,
      @Schema(
              minimum = "0",
              description =
                  "GET /api/v1/admin/special-event-reviews?status=PENDING 전체 후보 수. 근거가 만료·비활성인 재검토 대상도 포함합니다. CONFIRMED·DISMISSED 처리 시 제외합니다.")
          long specialEventReviewCount,
      @Schema(
              minimum = "0",
              description =
                  "GET /api/v1/admin/collection-failures?from={failureWindowStart}&to={checkedAt} 전체 작업 수. VIDEO·DISCOVERY의 FAILED·QUOTA_EXHAUSTED·TIMED_OUT 작업을 실패 종료 시각(없으면 시작 시각) 기준 최근 24시간 집계합니다. 실패 영상 수·예약 누락 수는 아니며 성공 상태로 변경되거나 해당 원본 작업의 재시도 요청이 SUCCEEDED이면 제외합니다. 별도 실패 실행은 각각 셉니다.")
          long collectionFailureCount) {}
}
