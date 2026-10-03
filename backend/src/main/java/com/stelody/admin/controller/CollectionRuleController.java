package com.stelody.admin.controller;

import com.stelody.admin.dto.CatalogAdminDtos.AuditPage;
import com.stelody.admin.dto.CollectionRuleDtos.*;
import com.stelody.admin.service.CollectionRuleService;
import com.stelody.auth.domain.SessionUser;
import com.stelody.collector.repository.CollectionRuleStore.Snapshot;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/collection-rules")
public class CollectionRuleController {
  private final CollectionRuleService service;

  public CollectionRuleController(CollectionRuleService service) {
    this.service = service;
  }

  private <T> ResponseEntity<T> response(T value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }

  @GetMapping
  public ResponseEntity<Snapshot> current() {
    return response(service.current());
  }

  @PutMapping
  public ResponseEntity<Snapshot> change(
      @Valid @RequestBody Change input, @AuthenticationPrincipal SessionUser user) {
    return response(service.change(input, user.id()));
  }

  @PostMapping("/preview")
  public ResponseEntity<PreviewResult> preview(@Valid @RequestBody Preview input) {
    return response(service.preview(input));
  }

  @GetMapping("/audit")
  public ResponseEntity<AuditPage> audits(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return response(service.audits(page, size));
  }
}
