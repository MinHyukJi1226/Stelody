package com.stelody.auth.controller;

import com.stelody.auth.domain.LoginReturn;
import com.stelody.auth.domain.SessionUser;
import com.stelody.auth.web.ApiProblems;
import com.stelody.user.dto.CurrentUser;
import com.stelody.user.service.AccountService;
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
  public CsrfResponse csrf(CsrfToken csrf) {
    return new CsrfResponse(csrf.getHeaderName(), csrf.getToken());
  }

  @GetMapping("/api/v1/auth/google")
  public void google(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (clients.getIfAvailable() == null) {
      problems.write(request, response, 503, "GOOGLE_LOGIN_UNAVAILABLE", "Google 로그인 설정이 필요합니다");
    } else {
      var target = (LoginReturn) request.getAttribute(LoginReturn.ATTRIBUTE);
      response.sendRedirect(
          "/api/v1/auth/authorize/google"
              + (target == null
                  ? ""
                  : "?returnTo=" + URLEncoder.encode(target.path(), StandardCharsets.UTF_8)));
    }
  }

  @GetMapping("/api/v1/me")
  public CurrentUser me(@AuthenticationPrincipal SessionUser user) {
    return accounts.find(user.id()).orElseThrow();
  }

  public record CsrfResponse(String headerName, String token) {}
}
