package com.stelody.auth.service;

import com.stelody.auth.domain.*;
import com.stelody.export.service.YouTubeConnectionService;
import com.stelody.user.repository.WithdrawalRepository;
import com.stelody.user.web.AccountException;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReauthenticationService {
  private final WithdrawalRepository store;
  private final ObjectProvider<ClientRegistrationRepository> clients;
  private final TransactionTemplate transaction;

  public record Authorization(OAuth2AuthorizationRequest request, Instant expiresAt) {}

  public record Confirmation(boolean reauthenticated, Instant expiresAt) {}

  public ReauthenticationService(
      WithdrawalRepository store,
      ObjectProvider<ClientRegistrationRepository> clients,
      PlatformTransactionManager manager) {
    this.store = store;
    this.clients = clients;
    transaction = new TransactionTemplate(manager);
    transaction.setTimeout(10);
  }

  public Authorization start(SessionUser user, HttpServletRequest request) {
    var registrations = clients.getIfAvailable();
    if (registrations == null || registrations.findByRegistrationId("google") == null)
      throw new AccountException(
          503, "GOOGLE_REAUTHENTICATION_UNAVAILABLE", "Google 재인증 설정이 필요합니다");
    String session = YouTubeConnectionService.hash(request.getSession().getId());
    Instant expires = Instant.now().plusSeconds(300);
    var context = new ReauthenticationContext(user, session, UUID.randomUUID(), expires);
    var resolver =
        new DefaultOAuth2AuthorizationRequestResolver(registrations, "/api/v1/auth/authorize");
    resolver.setAuthorizationRequestCustomizer(
        builder -> {
          OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
          builder.scopes(Set.of("openid", "email"));
          builder.attributes(a -> a.put(ReauthenticationContext.ATTRIBUTE, context));
          builder.additionalParameters(p -> p.put("prompt", "select_account"));
        });
    var authorization = resolver.resolve(request, "google");
    transaction.executeWithoutResult(
        tx -> {
          store.lockActive(user.id());
          store.pending(
              user.id(),
              session,
              context.generation(),
              YouTubeConnectionService.hash(authorization.getState()),
              expires);
        });
    return new Authorization(authorization, expires);
  }

  public void consume(ReauthenticationContext context, String state) {
    try {
      transaction.executeWithoutResult(
          tx -> {
            store.lockActive(context.user().id());
            if (!store.consume(
                context.user().id(),
                context.sessionHash(),
                context.generation(),
                YouTubeConnectionService.hash(state)))
              throw new OAuth2AuthenticationException("invalid_reauthentication");
          });
    } catch (RuntimeException e) {
      throw new OAuth2AuthenticationException("invalid_reauthentication");
    }
  }

  public void confirm(ReauthenticationContext context, String session) {
    transaction.executeWithoutResult(
        tx -> {
          store.lockActive(context.user().id());
          if (!Instant.now().isBefore(context.user().signedInAt().plusSeconds(43200)))
            throw new AccountException(401, "SESSION_EXPIRED", "다시 로그인해 주세요");
          if (!store.confirm(
              context.user().id(),
              context.sessionHash(),
              context.generation(),
              YouTubeConnectionService.hash(session),
              Instant.now().plusSeconds(300))) throw AccountException.reauthenticationRequired();
        });
  }

  public Confirmation confirmation(UUID user, String session) {
    var expires = store.confirmation(user, YouTubeConnectionService.hash(session));
    return new Confirmation(expires.isPresent(), expires.orElse(null));
  }
}
