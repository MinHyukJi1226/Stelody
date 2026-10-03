package com.stelody.admin.controller;

import com.stelody.admin.dto.CatalogAdminDtos.AuditPage;
import com.stelody.admin.dto.SpecialReviewDtos.*;
import com.stelody.admin.service.SpecialReviewService;
import com.stelody.auth.domain.SessionUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class SpecialReviewController {
  private final SpecialReviewService service;

  public SpecialReviewController(SpecialReviewService service) {
    this.service = service;
  }

  private <T> ResponseEntity<T> response(T value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }

  @GetMapping("/special-event-reviews")
  public ResponseEntity<Page> list(
      @RequestParam(required = false) Status status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return response(service.list(status, page, size));
  }

  @GetMapping("/special-event-reviews/{id}")
  public ResponseEntity<Item> detail(@PathVariable UUID id) {
    return response(service.detail(id));
  }

  @PatchMapping("/special-event-reviews/{id}")
  public ResponseEntity<Item> change(
      @PathVariable UUID id,
      @Valid @RequestBody Change input,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.change(id, input, user.id()));
  }

  @GetMapping("/special-event-reviews/{id}/audit")
  public ResponseEntity<AuditPage> audits(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return response(service.audits(id, page, size));
  }

  @PostMapping("/songs/{id}/special-event-review")
  public ResponseEntity<Refresh> refresh(@PathVariable UUID id) {
    return response(service.refresh(id));
  }
}
