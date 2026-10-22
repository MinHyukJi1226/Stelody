package com.stelody.auth.web;

import com.stelody.auth.domain.LocalLogin;
import com.stelody.auth.domain.LoginReturn;
import com.stelody.auth.domain.SessionUser;
import com.stelody.auth.service.GoogleOidcUserService.LocalOidcUser;
import com.stelody.auth.service.PersistentLoginService;
import com.stelody.auth.service.ReauthenticationService;
import com.stelody.user.web.AccountException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Component
public class LoginHandlers {
  private final PersistentLoginService persistent;
  private final ApiProblems problems;
  private final ReauthenticationService reauthentication;

  public LoginHandlers(
      ApiProblems problems,
      ReauthenticationService reauthentication,
      PersistentLoginService persistent) {
    this.persistent = persistent;
    this.problems = problems;
    this.reauthentication = reauthentication;
  }

  public void success(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    var user = (LocalOidcUser) authentication.getPrincipal();
    var previous = user.reauthentication();
    SessionUser principal;
    try {
      principal =
          previous == null
              ? persistent.issue(user.account().id(), request, response)
              : previous.user();
    } catch (DataAccessException | TransactionException e) {
      SecurityContextHolder.clearContext();
      if (request.getSession(false) != null) request.getSession(false).invalidate();
      problems.write(
          request, response, 503, "LOGIN_STORAGE_UNAVAILABLE", "로그인을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요");
      return;
    }
    var local =
        UsernamePasswordAuthenticationToken.authenticated(principal, null, user.getAuthorities());
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(local);
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    if (previous != null) {
      // Store only local identity even if confirming the proof fails; never persist Google tokens.
      try {
        reauthentication.confirm(previous, request.getSession().getId());
        persistent.reauthenticated(previous.user(), request, response);
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
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    var target = (LoginReturn) request.getAttribute(LoginReturn.ATTRIBUTE);
    response.sendRedirect(
        LocalLogin.location(request, target == null ? "/api/v1/me" : target.location("success")));
  }

  public void failure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    // A public callback failure must not destroy an existing authenticated session.
    // Spring removes the matching pending authorization request during callback processing.
    SecurityContextHolder.clearContext();
    response.setHeader("Referrer-Policy", "no-referrer");
    var target = (LoginReturn) request.getAttribute(LoginReturn.ATTRIBUTE);
    if (target != null) {
      String result =
          exception instanceof OAuth2AuthenticationException oauth
                  && oauth.getError().getErrorCode().equals("access_denied")
              ? "cancelled"
              : "failed";
      response.setHeader("Cache-Control", "no-store");
      response.sendRedirect(LocalLogin.location(request, target.location(result)));
      return;
    }
    problems.write(request, response, 401, "GOOGLE_LOGIN_FAILED", "Google 로그인을 완료하지 못했습니다");
  }
}
