package com.stelody.collection.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.collection.dto.CollectionDtos.*;
import com.stelody.collection.service.CollectionOperations;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class CollectionController {
  private final CollectionOperations service;

  public CollectionController(CollectionOperations service) {
    this.service = service;
  }

  @GetMapping("/collection-status")
  @Operation(summary = "수집 운영 현황")
  public ResponseEntity<Overview> overview() {
    return ok(service.overview());
  }

  @GetMapping("/collection-runs")
  @Operation(summary = "수집 실행 목록")
  public ResponseEntity<Page> list(
      @RequestParam(defaultValue = "VIDEO") String kind,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status) {
    return ok(service.list(kind, page, size, status));
  }

  @GetMapping("/collection-runs/{kind}/{id}")
  @Operation(summary = "수집 실행 상세")
  public ResponseEntity<Run> detail(@PathVariable String kind, @PathVariable UUID id) {
    return ok(service.detail(kind, id));
  }

  @GetMapping("/collection-retries/{id}")
  @Operation(summary = "수집 재시도 요청 조회")
  public ResponseEntity<Retry> retry(@PathVariable UUID id) {
    return ok(service.retry(id));
  }

  @PostMapping("/collection-runs/{kind}/{id}/retries")
  @Operation(summary = "실패한 수집 재시도 접수")
  public ResponseEntity<Retry> request(
      @PathVariable String kind,
      @PathVariable UUID id,
      @AuthenticationPrincipal SessionUser user,
      @Valid @RequestBody RetryInput input) {
    var value = service.request(kind, id, user.id(), input);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/admin/collection-retries/" + value.id()))
        .cacheControl(CacheControl.noStore())
        .body(value);
  }

  private <T> ResponseEntity<T> ok(T value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }
}
