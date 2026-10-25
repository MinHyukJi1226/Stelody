package com.stelody.review.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.review.dto.ReviewDtos;
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

  public ReviewController(ReviewService service) {
    this.service = service;
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
      summary = "검토 완료 대상 확인",
      description =
          "신규 검토(PENDING+유효 REVIEW)의 ID·버전 목록을 최대 10000건 반환합니다. hasMore가 true면 나머지는 다음 검토 완료에서 처리합니다. 조회 이후 새로 들어온 후보는 삭제 대상에 포함되지 않습니다.")
  public ResponseEntity<ReviewDtos.CompletionPreview> completionPreview() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.completionPreview());
  }

  @PostMapping("/completion")
  @Operation(
      summary = "검토 완료 후 미등록 후보 영구 삭제",
      description =
          "대상 확인 응답의 items를 전달합니다. 관리자·CSRF가 필요하며 후보와 종속 검토 이력을 영구 삭제합니다. 등록된 곡·등록 완료 후보·보류/제외 후보·수집 지점은 보존합니다. 대상이 변경되면 409 REVIEW_VERSION_CONFLICT로 전체 취소합니다. 이미 삭제되거나 등록된 대상은 건너뛰어 동일 요청 재전송이 가능합니다. 신규 대상을 자동으로 다시 조회하여 재전송하지 않습니다. 응답은 실제 deletedCount입니다.")
  public ResponseEntity<ReviewDtos.CompletionResult> complete(
      @AuthenticationPrincipal SessionUser user, @Valid @RequestBody ReviewDtos.Complete body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.complete(user.id(), body));
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
