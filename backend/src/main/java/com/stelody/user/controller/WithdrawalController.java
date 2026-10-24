package com.stelody.user.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.auth.service.ReauthenticationService;
import com.stelody.auth.web.ReauthenticationRequests;
import com.stelody.user.service.WithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.*;
import java.time.Instant;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
public class WithdrawalController {
  private final ReauthenticationService reauthentication;
  private final ReauthenticationRequests authorizations;
  private final WithdrawalService withdrawals;

  public WithdrawalController(
      ReauthenticationService reauthentication,
      ReauthenticationRequests authorizations,
      WithdrawalService withdrawals) {
    this.reauthentication = reauthentication;
    this.authorizations = authorizations;
    this.withdrawals = withdrawals;
  }

  @Schema(requiredProperties = {"authorizationUrl", "expiresAt"})
  public record Authorization(String authorizationUrl, Instant expiresAt) {}

  public record ReauthenticationInput(
      @Schema(
              description = "사이트 내부 UI 경로. 생략하면 기존 재인증 확인 API로 복귀",
              maxLength = 2048,
              nullable = true)
          String returnTo,
      @Schema(
              description = "서버에 등록된 콜백 선택",
              allowableValues = {"default", "local"},
              defaultValue = "default",
              nullable = true)
          String callbackClient) {}

  @PostMapping("/api/v1/me/reauthentications")
  @Operation(summary = "탈퇴를 위한 Google 재인증 시작")
  public ResponseEntity<Authorization> start(
      @AuthenticationPrincipal SessionUser user,
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestBody(required = false) ReauthenticationInput input) {
    var flow =
        reauthentication.start(
            user,
            request,
            input == null ? null : input.returnTo(),
            input == null ? null : input.callbackClient());
    authorizations.saveAuthorizationRequest(flow.request(), request, response);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .body(new Authorization(flow.request().getAuthorizationRequestUri(), flow.expiresAt()));
  }

  @GetMapping("/api/v1/me/withdrawal-confirmation")
  @Operation(summary = "탈퇴 재인증 유효 여부 조회")
  public ResponseEntity<ReauthenticationService.Confirmation> confirmation(
      @AuthenticationPrincipal SessionUser user, HttpServletRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reauthentication.confirmation(user.id(), request.getSession().getId()));
  }

  @DeleteMapping("/api/v1/me")
  @Operation(summary = "회원 탈퇴 확정")
  public ResponseEntity<Void> withdraw(
      @AuthenticationPrincipal SessionUser user,
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestHeader(
              value = com.stelody.user.service.WithdrawalResults.REQUEST_HEADER,
              required = false)
          String requestId) {
    withdrawals.withdraw(user.id(), request.getSession().getId(), requestId);
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from(com.stelody.auth.service.PersistentLoginService.COOKIE, "")
            .secure(true)
            .httpOnly(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
    request.getSession().invalidate();
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
