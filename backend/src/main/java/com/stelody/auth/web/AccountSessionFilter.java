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
  private final AccountService accounts;
  private final ApiProblems problems;
  private final ObjectProvider<FindByIndexNameSessionRepository<?>> sessions;

  public AccountSessionFilter(
      AccountService accounts,
      ApiProblems problems,
      ObjectProvider<FindByIndexNameSessionRepository<?>> sessions) {
    this.accounts = accounts;
    this.problems = problems;
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof SessionUser user) {
      var account = accounts.find(user.id());
      boolean unavailable = account.isEmpty() || !"ACTIVE".equals(account.get().status());
      boolean expired = !Instant.now().isBefore(user.signedInAt().plus(Duration.ofHours(12)));
      if (unavailable || expired) {
        if (unavailable && sessions.getIfAvailable() != null) {
          var repository = sessions.getObject();
          repository.findByPrincipalName(user.getName()).keySet().forEach(repository::deleteById);
        }
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        problems.write(request, response, 401, "SESSION_EXPIRED", "다시 로그인해 주세요");
        return;
      }
      SecurityContextHolder.getContext()
          .setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  user, null, List.of(new SimpleGrantedAuthority("ROLE_" + account.get().role()))));
    }
    chain.doFilter(request, response);
  }
}
