package com.stelody.admin.controller;

import com.stelody.admin.dto.InboxDtos.Overview;
import com.stelody.admin.service.InboxService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InboxController {
  private final InboxService service;

  public InboxController(InboxService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/admin/inbox")
  @Operation(
      summary = "관리자 작업함 집계",
      description =
          "기존 검토·정보 보완·기념일 목록과 동일한 조건으로 전체 건수를 집계합니다. 수집 실패 목록에는 응답의 failureWindowStart·checkedAt을 from·to로 전달합니다. 한 응답은 같은 DB 스냅샷에서 집계하며 이후 조회 사이 처리·수집·시간 경과로 숫자가 달라질 수 있습니다.")
  public ResponseEntity<Overview> overview() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overview());
  }
}
