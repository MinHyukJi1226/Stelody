package com.stelody.auth.web;

import com.stelody.auth.domain.LocalLogin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Restore the public callback URL only from a matching server-side OAuth request, never headers.
 */
public final class LocalLoginCallbackFilter extends OncePerRequestFilter {
  private final ReauthenticationRequests requests;

  public LocalLoginCallbackFilter(ReauthenticationRequests requests) {
    this.requests = requests;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getMethod().equals("GET")
        || !request.getServletPath().equals("/api/v1/auth/callback/google");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authorization = requests.loadAuthorizationRequest(request);
    LocalLogin local =
        authorization == null ? null : authorization.getAttribute(LocalLogin.ATTRIBUTE);
    if (local == null) {
      chain.doFilter(request, response);
      return;
    }
    URI callback = URI.create(local.callbackUri());
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
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
            return "https".equals(callback.getScheme());
          }

          @Override
          public StringBuffer getRequestURL() {
            return new StringBuffer(local.callbackUri());
          }
        },
        response);
  }
}
