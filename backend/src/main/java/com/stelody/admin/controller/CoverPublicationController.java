package com.stelody.admin.controller;

import com.stelody.admin.dto.CatalogAdminDtos.AuditPage;
import com.stelody.admin.dto.CoverPublicationDtos.*;
import com.stelody.admin.service.CoverPublicationService;
import com.stelody.auth.domain.SessionUser;
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
  public ResponseEntity<Control> current() {
    return response(service.current());
  }

  @PutMapping
  public ResponseEntity<Control> change(
      @Valid @RequestBody Change input, @AuthenticationPrincipal SessionUser user) {
    return response(service.change(input, user.id()));
  }

  @GetMapping("/registrations")
  public ResponseEntity<Page> registrations(
      @RequestParam(defaultValue = "true") boolean incompleteOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return response(service.registrations(incompleteOnly, page, size));
  }

  @GetMapping("/audit")
  public ResponseEntity<AuditPage> audits(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return response(service.audits(page, size));
  }
}
