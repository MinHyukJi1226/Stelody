package com.stelody.auth.web;

import com.stelody.auth.domain.*;
import com.stelody.auth.domain.ReauthenticationReturn.Error;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** A dedicated route identifies callback failures even when the session/state has disappeared. */
public class ReauthenticationCallbackFilter extends OncePerRequestFilter {
  private final ReauthenticationRequests requests;
  private final ReauthenticationClients clients;
  private final ReauthenticationRedirects redirects;

  public ReauthenticationCallbackFilter(
      ReauthenticationRequests requests,
      ReauthenticationClients clients,
      ReauthenticationRedirects redirects) {
    this.requests = requests;
    this.clients = clients;
    this.redirects = redirects;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest r) {
    return !ReauthenticationRedirects.isCallback(r);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
      throws ServletException, IOException {
    var authorization = requests.loadAuthorizationRequest(r);
    ReauthenticationContext context =
        authorization == null
            ? null
            : authorization.getAttribute(ReauthenticationContext.ATTRIBUTE);
    ReauthenticationReturn target = context == null ? null : context.target();
    String client =
        r.getServletPath().equals(ReauthenticationReturn.LOCAL_CALLBACK) ? "local" : "default";
    boolean matched =
        target != null
            && target.client().equals(client)
            && target.callbackUri().equals(clients.uri(client))
            && single(r, "state", true)
            && single(r, "code", false)
            && single(r, "error", false)
            && ((r.getParameter("code") == null) != (r.getParameter("error") == null));
    if (matched) r.setAttribute(ReauthenticationReturn.ATTRIBUTE, target);
    Error error = (Error) r.getAttribute(ReauthenticationReturn.ERROR_ATTRIBUTE);
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (error == null
        && (auth == null
            || !(auth.getPrincipal() instanceof SessionUser)
            || r.getSession(false) == null)) error = Error.SESSION_EXPIRED;
    if (error == null
        && (!matched
            || !single(r, "state", true)
            || !single(r, "code", false)
            || !single(r, "error", false)
            || ((r.getParameter("code") == null) == (r.getParameter("error") == null))))
      error = Error.REAUTHENTICATION_REQUEST_INVALID;
    if (error == null && !Instant.now().isBefore(context.expiresAt()))
      error = Error.REAUTHENTICATION_REQUEST_EXPIRED;
    if (error != null) {
      redirects.respond(r, s, "failed", error);
      return;
    }
    URI callback = URI.create(target.callbackUri());
    chain.doFilter(
        new HttpServletRequestWrapper(r) {
          @Override
          public String getScheme() {
            return callback.getScheme();
          }

          @Override
          public String getServerName() {
            return callback.getHost();
          }

          @Override
          public int getServerPort() {
            return callback.getPort() >= 0 ? callback.getPort() : (isSecure() ? 443 : 80);
          }

          @Override
          public boolean isSecure() {
            return callback.getScheme().equals("https");
          }

          @Override
          public StringBuffer getRequestURL() {
            return new StringBuffer(target.callbackUri());
          }
        },
        s);
  }

  private static boolean single(HttpServletRequest r, String key, boolean required) {
    String[] v = r.getParameterValues(key);
    return v == null ? !required : v.length == 1 && v[0] != null && !v[0].isEmpty();
  }
}
