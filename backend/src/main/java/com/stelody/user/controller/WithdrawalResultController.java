package com.stelody.user.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.user.service.WithdrawalResults;
import com.stelody.user.web.AccountException;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
public class WithdrawalResultController {
  private final WithdrawalResults results;

  public WithdrawalResultController(WithdrawalResults results) {
    this.results = results;
  }

  @PostMapping("/api/v1/me/withdrawal-requests")
  @Operation(summary = "탈퇴 전 결과 조회 ID·권한 발급")
  public ResponseEntity<WithdrawalResults.Prepared> prepare(
      @AuthenticationPrincipal SessionUser user) {
    return ResponseEntity.status(201)
        .cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .body(results.prepare(user.id()));
  }

  @GetMapping("/api/v1/withdrawal-requests/{id}")
  @Operation(summary = "탈퇴 요청 결과 읽기 전용 조회")
  public ResponseEntity<WithdrawalResults.Result> result(
      @PathVariable String id,
      @RequestHeader(value = WithdrawalResults.TOKEN_HEADER, required = false) String token) {
    WithdrawalResults.Result result;
    try {
      result = results.get(id, token);
    } catch (DataAccessException | TransactionException e) {
      throw new AccountException(
          503, "WITHDRAWAL_RESULT_STORAGE_UNAVAILABLE", "탈퇴 결과 저장소를 확인하지 못했습니다");
    }
    var response =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer");
    if (result.pollAfterSeconds() != null)
      response.header("Retry-After", result.pollAfterSeconds().toString());
    return response.body(result);
  }
}
