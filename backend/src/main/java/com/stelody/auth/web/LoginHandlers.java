package com.stelody.auth.web;

import com.stelody.auth.domain.SessionUser;
import com.stelody.auth.service.GoogleOidcUserService.LocalOidcUser;
import com.stelody.auth.service.ReauthenticationService;
import com.stelody.user.web.AccountException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Component
public class LoginHandlers {
  private final ApiProblems problems;
  private final ReauthenticationService reauthentication;

  public LoginHandlers(ApiProblems problems, ReauthenticationService reauthentication) {
    this.problems = problems;
    this.reauthentication = reauthentication;
  }

  public void success(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    var user = (LocalOidcUser) authentication.getPrincipal();
    var previous = user.reauthentication();
    var local =
        UsernamePasswordAuthenticationToken.authenticated(
            previous == null
                ? new SessionUser(user.account().id(), Instant.now())
                : previous.user(),
            null,
            user.getAuthorities());
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(local);
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    if (previous != null) {
      // Store only local identity even if confirming the proof fails; never persist Google tokens.
      try {
        reauthentication.confirm(previous, request.getSession().getId());
      } catch (AccountException e) {
        problems.write(request, response, e.status(), e.code(), e.getMessage());
        return;
      } catch (DataAccessException | TransactionException e) {
        problems.write(request, response, 503, "ACCOUNT_STORAGE_UNAVAILABLE", "재인증을 완료하지 못했습니다");
        return;
      }
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Referrer-Policy", "no-referrer");
      response.sendRedirect("/api/v1/me/withdrawal-confirmation");
      return;
    }
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
