package com.stelody.auth.controller;

import com.stelody.auth.domain.LocalLogin;
import com.stelody.auth.domain.LoginReturn;
import com.stelody.auth.domain.SessionUser;
import com.stelody.auth.web.ApiProblems;
import com.stelody.user.dto.CurrentUser;
import com.stelody.user.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
  private final AccountService accounts;
  private final ObjectProvider<ClientRegistrationRepository> clients;
  private final ApiProblems problems;

  public AuthController(
      AccountService accounts,
      ObjectProvider<ClientRegistrationRepository> clients,
      ApiProblems problems) {
    this.accounts = accounts;
    this.clients = clients;
    this.problems = problems;
  }

  @GetMapping("/api/v1/auth/csrf")
  @Operation(
      summary = "CSRF 토큰 조회",
      description =
          "일반 사용자 로그인 유지 쿠키가 유효하면 세션을 먼저 복구합니다. 같은 기기의 짧은 세션 복구에는 기존 유효 CSRF 헤더를 사용할 수 있습니다. 로그인·명시적 Google 재인증·로그아웃 후에는 토큰을 다시 조회합니다. CSRF 누락·불일치는 복구 후에도 403입니다.")
  public CsrfResponse csrf(@io.swagger.v3.oas.annotations.Parameter(hidden = true) CsrfToken csrf) {
    return new CsrfResponse(csrf.getHeaderName(), csrf.getToken());
  }

  @GetMapping("/api/v1/auth/google")
  @Operation(
      summary = "Google 로그인 시작",
      description =
          "일반 USER의 성공한 Google 로그인은 30일 고정 기한의 STELODY_LOGIN 쿠키도 발급합니다. Secure·HttpOnly·SameSite=Lax·Path=/를 유지하며 프론트가 토큰을 읽거나 별도 저장하지 않습니다. 세션 비활동 30분·최대 12시간 후에는 다음 API 요청에서 자동 복구합니다. ADMIN은 발급하지 않습니다. 자동 복구는 탈퇴 재인증을 대신하지 않으며 30일 기한을 연장하지 않습니다.")
  public void google(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (clients.getIfAvailable() == null) {
      problems.write(request, response, 503, "GOOGLE_LOGIN_UNAVAILABLE", "Google 로그인 설정이 필요합니다");
    } else {
      var target = (LoginReturn) request.getAttribute(LoginReturn.ATTRIBUTE);
      String path =
          "/api/v1/auth/authorize/google"
              + (target == null
                  ? ""
                  : "?returnTo=" + URLEncoder.encode(target.path(), StandardCharsets.UTF_8));
      if (request.getAttribute(LocalLogin.ATTRIBUTE) != null)
        path += (target == null ? "?" : "&") + "loginClient=local";
      response.sendRedirect(LocalLogin.location(request, path));
    }
  }

  @GetMapping("/api/v1/me")
  @Operation(summary = "현재 로그인 계정 조회")
  public CurrentUser me(@AuthenticationPrincipal SessionUser user) {
    return accounts.find(user.id()).orElseThrow();
  }

  @Schema(requiredProperties = {"headerName", "token"})
  public record CsrfResponse(String headerName, String token) {}
}
