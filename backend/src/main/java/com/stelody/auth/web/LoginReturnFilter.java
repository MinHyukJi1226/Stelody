package com.stelody.auth.web;

import com.stelody.auth.domain.LoginReturn;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Validate both the public start route and direct OAuth start before any session is created. */
public final class LoginReturnFilter extends OncePerRequestFilter {
  private static final Set<String> STARTS =
      Set.of("/api/v1/auth/google", "/api/v1/auth/authorize/google");
  private final ApiProblems problems;

  public LoginReturnFilter(ApiProblems problems) {
    this.problems = problems;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getMethod().equals("GET") || !STARTS.contains(request.getServletPath());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    var values = request.getParameterValues("returnTo");
    if (values != null) {
      try {
        if (values.length != 1) throw new IllegalArgumentException("Duplicate login return path");
        request.setAttribute(
            LoginReturn.ATTRIBUTE,
            new LoginReturn(LoginReturn.validate(values[0]), Instant.now().plusSeconds(300)));
      } catch (IllegalArgumentException e) {
        problems.write(request, response, 400, "INVALID_LOGIN_RETURN", "로그인 복귀 경로가 올바르지 않습니다");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
