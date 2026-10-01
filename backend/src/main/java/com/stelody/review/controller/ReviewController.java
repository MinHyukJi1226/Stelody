package com.stelody.review.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.review.dto.ReviewDtos;
import com.stelody.review.service.ReviewService;
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
  public ResponseEntity<ReviewDtos.Page> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "PENDING") String status,
      @RequestParam(required = false) String disposition) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.list(page, size, status, disposition));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ReviewDtos.Item> detail(@PathVariable UUID id) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(id));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<ReviewDtos.Item> change(
      @PathVariable UUID id,
      @AuthenticationPrincipal SessionUser user,
      @Valid @RequestBody ReviewDtos.Change body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.change(id, user.id(), body));
  }
}
