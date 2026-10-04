package com.stelody.admin.controller;

import com.stelody.admin.dto.CatalogAdminDtos.AuditPage;
import com.stelody.admin.dto.CoverPublicationDtos.*;
import com.stelody.admin.service.CoverPublicationService;
import com.stelody.auth.domain.SessionUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/cover-auto-publication")
public class CoverPublicationController {
  private final CoverPublicationService service;

  public CoverPublicationController(CoverPublicationService service) {
    this.service = service;
  }

  private <T> ResponseEntity<T> response(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  @GetMapping
  @Operation(summary = "커버 자동 공개 설정")
  public ResponseEntity<Control> current() {
    return response(service.current());
  }

  @PutMapping
  @Operation(summary = "커버 자동 공개 설정 변경")
  public ResponseEntity<Control> change(
      @Valid @RequestBody Change input, @AuthenticationPrincipal SessionUser user) {
    return response(service.change(input, user.id()));
  }

  @GetMapping("/registrations")
  @Operation(summary = "자동 등록한 커버 목록")
  public ResponseEntity<Page> registrations(
      @RequestParam(defaultValue = "true") boolean incompleteOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return response(service.registrations(incompleteOnly, page, size));
  }

  @GetMapping("/audit")
  @Operation(summary = "커버 자동 공개 설정 변경 이력")
  public ResponseEntity<AuditPage> audits(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return response(service.audits(page, size));
  }
}
