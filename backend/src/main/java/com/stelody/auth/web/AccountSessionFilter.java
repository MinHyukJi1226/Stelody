package com.stelody.auth.web;

import com.stelody.auth.domain.SessionUser;
import com.stelody.user.service.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccountSessionFilter extends OncePerRequestFilter {
  private final com.stelody.auth.service.PersistentLoginService persistent;
  private final AccountService accounts;
  private final ApiProblems problems;
  private final ObjectProvider<FindByIndexNameSessionRepository<?>> sessions;

  public AccountSessionFilter(
      AccountService accounts,
      ApiProblems problems,
      ObjectProvider<FindByIndexNameSessionRepository<?>> sessions,
      com.stelody.auth.service.PersistentLoginService persistent) {
    this.persistent = persistent;
    this.accounts = accounts;
    this.problems = problems;
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      if (!prepare(request, response)) return;
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException e) {
      problems.write(
          request,
          response,
          503,
          "LOGIN_STORAGE_UNAVAILABLE",
          "로그인 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요");
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean prepare(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof SessionUser user) {
      var account = accounts.find(user.id());
      boolean unavailable = account.isEmpty() || !"ACTIVE".equals(account.get().status());
      boolean expired = !Instant.now().isBefore(user.signedInAt().plus(Duration.ofHours(12)));
      boolean grantValid = unavailable || persistent.valid(user);
      if (unavailable || expired || !grantValid) {
        if (unavailable) {
          persistent.revokeUser(user.id(), response);
          if (sessions.getIfAvailable() != null)
            sessions
                .getObject()
                .findByPrincipalName(user.getName())
                .keySet()
                .forEach(sessions.getObject()::deleteById);
        }
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        if (!unavailable
            && grantValid
            && !oauthEdge(request)
            && persistent.restore(request, response, user.id())) return true;
        if (!grantValid) persistent.clear(response);
        if (com.stelody.export.web.YouTubeOAuthReturn.isCallback(request)) return true;
        problems.write(request, response, 401, "SESSION_EXPIRED", "다시 로그인해 주세요");
        return false;
      }
      SecurityContextHolder.getContext()
          .setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  user, null, List.of(new SimpleGrantedAuthority("ROLE_" + account.get().role()))));
      return true;
    }
    if (auth == null
        && request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/")
        && !oauthEdge(request)) persistent.restore(request, response, null);
    return true;
  }

  private static boolean oauthEdge(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return List.of(
            "/api/v1/auth/google",
            "/api/v1/auth/authorize/google",
            "/api/v1/auth/callback/google",
            "/api/v1/me/youtube/callback",
            "/api/v1/me/youtube/callback/local")
        .contains(path);
  }
}
