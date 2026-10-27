package com.stelody.review.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.review.dto.ReviewDtos;
import com.stelody.review.service.ReviewBatchService;
import com.stelody.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/reviews")
public class ReviewController {
  private final ReviewService service;
  private final ReviewBatchService batches;

  public ReviewController(ReviewService service, ReviewBatchService batches) {
    this.service = service;
    this.batches = batches;
  }

  @GetMapping
  @Operation(summary = "수집 후보 목록")
  public ResponseEntity<ReviewDtos.Page> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "PENDING") String status,
      @RequestParam(required = false) String disposition) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.list(page, size, status, disposition));
  }

  @GetMapping("/completion")
  @Operation(
      summary = "일괄 등록·정리 대상 확인",
      description =
          "신규 검토(PENDING+유효 REVIEW)의 영상 정보·ID·버전을 최대 10000건 반환합니다. channelMembers는 채널 연결 참고 정보이며 참여자 확정 근거가 아닙니다. 이 목록을 검토 단위로 고정해 페이지 간 선택을 유지합니다. hasMore가 true면 나머지는 다음 검토에서 처리합니다. 조회 이후 새로 들어온 후보는 삭제 대상에 포함되지 않습니다.")
  public ResponseEntity<ReviewDtos.CompletionPreview> completionPreview() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.completionPreview());
  }

  @PostMapping("/completion")
  @Operation(
      summary = "선택 곡 일괄 등록·미선택 후보 영구 삭제",
      description =
          "대상 확인 응답 전체에서 id·version만 추린 items(최대 10000건), 새 requestId(UUID), selected(1~100건의 후보 id만)를 전달합니다. song·kind·곡별 사유는 입력하지 않습니다. 서버가 수집 정보와 현재 수집 분류 규칙으로 곡·대표 영상을 생성하고 items 중 미선택 신규 검토 후보와 종속 검토 이력을 영구 삭제합니다. "
              + "저장된 COVER/ORIGINAL 제안을 사용하고 UNKNOWN은 현재 제목 분류 규칙으로 재판정합니다. 재판정 후에도 UNKNOWN이면 기본 COVER로 등록하고 관리자가 추후 수정합니다. 이 기본값은 관리자 선택 등록에만 적용하며 수집 분류·자동 공개 정책은 변경하지 않습니다. 현재 분류와 충돌하면 409 CANDIDATE_CLASSIFICATION_REQUIRED로 전체 취소합니다. "
              + "곡명은 NFKC·공백 정리 후 명시적인 솔로 커버 크레딧만 제거하며 불명확하면 영상 제목을 초안으로 유지합니다. 빈 곡명·300자 초과·검색 정규화 400자 초과는 임의로 자르지 않고 같은 409로 거절합니다. fieldErrors의 selected[i].id와 CONTENT_TYPE_CONFLICT·CONTENT_REQUIRES_REVIEW·TITLE_REQUIRES_EDIT로 후보와 사유를 식별합니다. "
              + "COVER는 OFFICIAL_COVER, ORIGINAL은 제목에 MV/music video/뮤직비디오만 있으면 OFFICIAL_MV, audio/음원만 있으면 AUDIO이며 둘 다 있거나 없으면 OTHER로 저장합니다. 기존 솔로 크레딧 규칙으로 한 멤버가 명확하고 MEMBER 채널의 연결과도 일치할 때만 참여자를 확인합니다. 단체·유닛·듀엣·외부 참여·모호한 크레딧은 채널 소유자로 확정하지 않고 멤버·외부 아티스트 배열을 비워 둡니다. "
              + "후보에는 자동 공개에 필요한 방송 메타데이터가 없으므로 이 API는 항상 DRAFT로 저장합니다. 기존 자동 공개 기능·설정은 변경하지 않습니다. 원곡은 null, 별칭·외부 아티스트·링크·노래방은 빈 배열, 검색 확인은 UNCHECKED, 특별 목적은 미설정(false/null)이며 추측해 채우지 않습니다. 누락 정보는 기존 관리자 곡 목록·상세의 missingFields로 보완하고 기존 수정 API로 수정·공개합니다. 솔로 자동 등록 전용 목록에는 추가하지 않습니다. 서버 고정 사유로 곡·후보별 감사 기록과 요청 ID별 BATCH_REGISTER 감사 기록을 남깁니다. "
              + "등록·삭제·감사 기록은 한 트랜잭션이며 하나라도 실패하거나 대상이 변경되면 전체 취소합니다. 새 후보·기존 등록 곡·등록 완료 후보·보류/제외 후보·수집 지점은 보존합니다. 관리자·CSRF가 필요합니다. 변경 충돌은 409 REVIEW_VERSION_CONFLICT이며 대상을 다시 확인합니다. 응답 유실 시 requestId와 본문 전체를 그대로 재전송합니다. 같은 요청은 등록된 후보에 보관한 요청 ID·본문 해시로 확인해 replayed=true, 기존 registeredSongs와 deletedCount=0(추가 삭제 없음)을 반환하며 정보 재구성·등록·삭제·감사 기록을 다시 하지 않습니다. 같은 요청의 내용을 바꾸면 충돌입니다.")
  public ResponseEntity<ReviewDtos.CompletionResult> complete(
      @AuthenticationPrincipal SessionUser user, @Valid @RequestBody ReviewDtos.Complete body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(batches.complete(user.id(), body));
  }

  @GetMapping("/{id}")
  @Operation(summary = "수집 후보 상세")
  public ResponseEntity<ReviewDtos.Item> detail(@PathVariable UUID id) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(id));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "수집 후보 검토 상태 변경")
  public ResponseEntity<ReviewDtos.Item> change(
      @PathVariable UUID id,
      @AuthenticationPrincipal SessionUser user,
      @Valid @RequestBody ReviewDtos.Change body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.change(id, user.id(), body));
  }
}
