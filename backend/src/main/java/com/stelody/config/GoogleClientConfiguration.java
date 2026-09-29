package com.stelody.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration
@Profile("google")
public class GoogleClientConfiguration {
  @Bean
  ClientRegistrationRepository googleClients(
      @Value("${GOOGLE_CLIENT_ID}") String clientId,
      @Value("${GOOGLE_CLIENT_SECRET}") String secret,
      @Value("${stelody.auth.google.redirect-uri}") String redirectUri) {
    URI callback = URI.create(redirectUri);
    boolean localHttp =
        "http".equals(callback.getScheme())
            && ("localhost".equals(callback.getHost()) || "127.0.0.1".equals(callback.getHost()));
    if (clientId.isBlank()
        || secret.isBlank()
        || callback.getHost() == null
        || !("https".equals(callback.getScheme()) || localHttp)
        || callback.getUserInfo() != null
        || callback.getFragment() != null
        || callback.getQuery() != null
        || !"/api/v1/auth/callback/google".equals(callback.getPath())) {
      throw new IllegalArgumentException(
          "Configure Google client credentials and an HTTPS callback (HTTP loopback allowed locally)");
    }
    return new InMemoryClientRegistrationRepository(
        CommonOAuth2Provider.GOOGLE
            .getBuilder("google")
            .clientId(clientId)
            .clientSecret(secret)
            .scope("openid", "email")
            .issuerUri("https://accounts.google.com")
            .redirectUri(redirectUri)
            .build());
  }
}
