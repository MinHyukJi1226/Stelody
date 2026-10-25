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
          "신규 검토(PENDING+유효 REVIEW)의 영상 정보·ID·버전을 최대 10000건 반환합니다. 채널에 연결된 멤버 ID는 channelMembers로 제공하며 등록 전 참여자를 확인합니다. 이 목록을 검토 단위로 고정해 페이지 간 선택을 유지합니다. hasMore가 true면 나머지는 다음 검토에서 처리합니다. 조회 이후 새로 들어온 후보는 삭제 대상에 포함되지 않습니다.")
  public ResponseEntity<ReviewDtos.CompletionPreview> completionPreview() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.completionPreview());
  }

  @PostMapping("/completion")
  @Operation(
      summary = "선택 곡 일괄 등록·미선택 후보 영구 삭제",
      description =
          "대상 확인 응답의 items, 새 requestId(UUID), selected(1~100건의 후보 id·song·kind)를 전달합니다. song은 기존 SongInput이며 version=0, representativeVideoId=null로 보냅니다. 선택한 곡을 새로 생성하고 해당 영상을 대표 영상으로 연결한 뒤 items 중 미선택 신규 검토 후보와 종속 검토 이력을 영구 삭제합니다. 등록·삭제는 한 트랜잭션이며 하나라도 실패하거나 대상이 변경되면 전체 취소합니다. 새 후보·기존 등록 곡·등록 완료 후보·보류/제외 후보·수집 지점은 보존합니다. 관리자·CSRF가 필요합니다. 변경 충돌은 409 REVIEW_VERSION_CONFLICT이며 대상을 다시 확인합니다. 응답 유실 시 requestId와 본문 전체를 그대로 재전송합니다. 같은 요청은 등록된 후보에 보관한 요청 ID·본문 해시로 확인해 replayed=true, 기존 registeredSongs와 deletedCount=0(추가 삭제 없음)을 반환합니다. 같은 요청의 내용을 바꾸면 충돌입니다. 서버는 제목·참여자·곡 유형을 임의로 추정하지 않습니다.")
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
