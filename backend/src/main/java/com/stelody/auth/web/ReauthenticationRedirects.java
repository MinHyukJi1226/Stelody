package com.stelody.auth.web;

import com.stelody.auth.domain.ReauthenticationReturn;
import com.stelody.auth.domain.ReauthenticationReturn.Error;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class ReauthenticationRedirects {
  private final ReauthenticationClients clients;
  private final ApiProblems problems;

  public ReauthenticationRedirects(ReauthenticationClients clients, ApiProblems problems) {
    this.clients = clients;
    this.problems = problems;
  }

  public static boolean isCallback(HttpServletRequest r) {
    return r.getMethod().equals("GET")
        && Set.of(ReauthenticationReturn.CALLBACK, ReauthenticationReturn.LOCAL_CALLBACK)
            .contains(r.getServletPath());
  }

  public boolean respond(HttpServletRequest r, HttpServletResponse s, String result, Error error)
      throws IOException {
    var target = (ReauthenticationReturn) r.getAttribute(ReauthenticationReturn.ATTRIBUTE);
    if (target == null && !isCallback(r)) return false;
    String client =
        r.getServletPath().equals(ReauthenticationReturn.LOCAL_CALLBACK) ? "local" : "default";
    if (target != null) client = target.client();
    s.setHeader("Cache-Control", "no-store");
    s.setHeader("Referrer-Policy", "no-referrer");
    if (clients.uri(client).isEmpty()) {
      problems.write(r, s, 503, "GOOGLE_REAUTHENTICATION_UNAVAILABLE", "재인증 콜백 설정이 필요합니다");
      return true;
    }
    if (target == null) target = new ReauthenticationReturn(null, client, clients.uri(client));
    s.setStatus(303);
    s.setHeader("Location", target.location(clients.origin(client), result, error));
    return true;
  }

  public boolean failure(
      HttpServletRequest r, HttpServletResponse s, AuthenticationException exception)
      throws IOException {
    String code =
        exception instanceof OAuth2AuthenticationException e ? e.getError().getErrorCode() : "";
    Error error =
        switch (code) {
          case "access_denied" -> Error.GOOGLE_REAUTHENTICATION_CANCELLED;
          case "reauthentication_account_mismatch" -> Error.REAUTHENTICATION_ACCOUNT_MISMATCH;
          case "reauthentication_request_expired" -> Error.REAUTHENTICATION_REQUEST_EXPIRED;
          case "invalid_reauthentication",
              "authorization_request_not_found",
              "invalid_state_parameter" ->
              Error.REAUTHENTICATION_REQUEST_INVALID;
          case "session_expired", "account_unavailable" -> Error.SESSION_EXPIRED;
          case "account_storage_unavailable" -> Error.ACCOUNT_STORAGE_UNAVAILABLE;
          default -> Error.GOOGLE_REAUTHENTICATION_FAILED;
        };
    return respond(
        r, s, error == Error.GOOGLE_REAUTHENTICATION_CANCELLED ? "cancelled" : "failed", error);
  }
}
