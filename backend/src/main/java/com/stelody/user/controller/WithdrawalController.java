package com.stelody.user.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.auth.service.ReauthenticationService;
import com.stelody.auth.web.ReauthenticationRequests;
import com.stelody.user.service.WithdrawalService;
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

  public record Authorization(String authorizationUrl, Instant expiresAt) {}

  @PostMapping("/api/v1/me/reauthentications")
  public ResponseEntity<Authorization> start(
      @AuthenticationPrincipal SessionUser user,
      HttpServletRequest request,
      HttpServletResponse response) {
    var flow = reauthentication.start(user, request);
    authorizations.saveAuthorizationRequest(flow.request(), request, response);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .body(new Authorization(flow.request().getAuthorizationRequestUri(), flow.expiresAt()));
  }

  @GetMapping("/api/v1/me/withdrawal-confirmation")
  public ResponseEntity<ReauthenticationService.Confirmation> confirmation(
      @AuthenticationPrincipal SessionUser user, HttpServletRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reauthentication.confirmation(user.id(), request.getSession().getId()));
  }

  @DeleteMapping("/api/v1/me")
  public ResponseEntity<Void> withdraw(
      @AuthenticationPrincipal SessionUser user, HttpServletRequest request) {
    withdrawals.withdraw(user.id(), request.getSession().getId());
    request.getSession().invalidate();
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
