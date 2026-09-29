package com.stelody.auth.web;

import com.stelody.auth.domain.SessionUser;
import com.stelody.auth.service.GoogleOidcUserService.LocalOidcUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
public class LoginHandlers {
  private final ApiProblems problems;

  public LoginHandlers(ApiProblems problems) {
    this.problems = problems;
  }

  public void success(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    var user = (LocalOidcUser) authentication.getPrincipal();
    var local =
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(user.account().id(), Instant.now()), null, user.getAuthorities());
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(local);
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    // Fixed same-origin destination: request parameters cannot create an open redirect.
    response.sendRedirect("/api/v1/me");
  }

  public void failure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    // A public callback failure must not destroy an existing authenticated session.
    // Spring removes the matching pending authorization request during callback processing.
    SecurityContextHolder.clearContext();
    problems.write(request, response, 401, "GOOGLE_LOGIN_FAILED", "Google 로그인을 완료하지 못했습니다");
  }
}
