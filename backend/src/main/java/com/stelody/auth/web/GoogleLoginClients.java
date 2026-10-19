package com.stelody.auth.web;

import com.stelody.auth.domain.LocalLogin;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

@Component
public class GoogleLoginClients {
  private final String localCallback;

  public GoogleLoginClients(
      @Value("${stelody.auth.google.local-redirect-uri:}") String localCallback) {
    if (!localCallback.isEmpty()) {
      URI uri = URI.create(localCallback);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || !("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !"/api/v1/auth/callback/google".equals(uri.getRawPath())
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || !uri.toASCIIString().equals(localCallback)) {
        throw new IllegalArgumentException(
            "Google local callback must use a loopback host and /api/v1/auth/callback/google");
      }
    }
    this.localCallback = localCallback;
  }

  public LocalLogin select(String[] clients) {
    if (clients == null) return null;
    if (clients.length != 1 || !"local".equals(clients[0]) || localCallback.isEmpty())
      throw new IllegalArgumentException("Unknown or disabled Google login client");
    return new LocalLogin(localCallback, Instant.now().plusSeconds(300));
  }

  public OAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository clients) {
    var standard = delegate(clients, false);
    var local = delegate(clients, true);
    return new OAuth2AuthorizationRequestResolver() {
      private DefaultOAuth2AuthorizationRequestResolver select(HttpServletRequest request) {
        return request.getAttribute(LocalLogin.ATTRIBUTE) == null ? standard : local;
      }

      @Override
      public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return select(request).resolve(request);
      }

      @Override
      public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return select(request).resolve(request, registrationId);
      }
    };
  }

  private DefaultOAuth2AuthorizationRequestResolver delegate(
      ClientRegistrationRepository clients, boolean local) {
    var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/api/v1/auth/authorize");
    resolver.setAuthorizationRequestCustomizer(
        builder -> {
          OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
          if (local) builder.redirectUri(localCallback);
        });
    return resolver;
  }
}
