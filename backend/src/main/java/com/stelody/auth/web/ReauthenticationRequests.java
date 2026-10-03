package com.stelody.auth.web;

import com.stelody.auth.domain.*;
import com.stelody.auth.service.ReauthenticationService;
import com.stelody.export.service.YouTubeConnectionService;
import jakarta.servlet.http.*;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

@Component
public class ReauthenticationRequests
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
  private final HttpSessionOAuth2AuthorizationRequestRepository delegate =
      new HttpSessionOAuth2AuthorizationRequestRepository();
  private final ReauthenticationService reauthentication;

  public ReauthenticationRequests(ReauthenticationService reauthentication) {
    this.reauthentication = reauthentication;
  }

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    return delegate.loadAuthorizationRequest(request);
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorization,
      HttpServletRequest request,
      HttpServletResponse response) {
    var target = (LoginReturn) request.getAttribute(LoginReturn.ATTRIBUTE);
    if (authorization != null
        && target != null
        && authorization.getAttribute(ReauthenticationContext.ATTRIBUTE) == null)
      authorization =
          OAuth2AuthorizationRequest.from(authorization)
              .attributes(a -> a.put(LoginReturn.ATTRIBUTE, target))
              .build();
    delegate.saveAuthorizationRequest(authorization, request, response);
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    var authorization = delegate.removeAuthorizationRequest(request, response);
    if (authorization == null) return null;
    ReauthenticationContext context = authorization.getAttribute(ReauthenticationContext.ATTRIBUTE);
    if (context != null) {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null
          || !(authentication.getPrincipal() instanceof SessionUser user)
          || !context.user().equals(user)
          || request.getSession(false) == null
          || !context
              .sessionHash()
              .equals(YouTubeConnectionService.hash(request.getSession(false).getId())))
        throw new OAuth2AuthenticationException("invalid_reauthentication");
      reauthentication.consume(context, authorization.getState());
      request.setAttribute(ReauthenticationContext.ATTRIBUTE, context);
    } else {
      LoginReturn target = authorization.getAttribute(LoginReturn.ATTRIBUTE);
      if (target != null) {
        if (!Instant.now().isBefore(target.expiresAt()))
          throw new OAuth2AuthenticationException("login_return_expired");
        request.setAttribute(LoginReturn.ATTRIBUTE, target);
      }
    }
    return authorization;
  }
}
