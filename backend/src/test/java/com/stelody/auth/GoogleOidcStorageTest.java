package com.stelody.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stelody.auth.domain.*;
import com.stelody.auth.service.GoogleOidcUserService;
import com.stelody.user.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

class GoogleOidcStorageTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @SuppressWarnings("unchecked")
  void databaseAndTransactionFailuresBecomeTheSameSafeOAuthError(boolean commitFailure) {
    var accounts = mock(AccountService.class);
    ObjectProvider<HttpServletRequest> provider = mock(ObjectProvider.class);
    var request = new MockHttpServletRequest();
    UUID user = UUID.randomUUID();
    Instant now = Instant.now();
    request.setAttribute(
        ReauthenticationContext.ATTRIBUTE,
        new ReauthenticationContext(
            new SessionUser(user, now), "hash", UUID.randomUUID(), now.plusSeconds(300)));
    when(provider.getObject()).thenReturn(request);
    when(accounts.reauthenticate(user, "subject"))
        .thenThrow(
            commitFailure
                ? new org.springframework.transaction.CannotCreateTransactionException(
                    "private transaction detail")
                : new org.springframework.dao.DataAccessResourceFailureException(
                    "private database detail"));
    var registration =
        CommonOAuth2Provider.GOOGLE
            .getBuilder("google")
            .clientId("client")
            .clientSecret("secret")
            .scope("openid")
            .redirectUri("https://api.example.test/callback")
            .userInfoUri("")
            .build();
    var token =
        new OidcIdToken(
            "test",
            now,
            now.plusSeconds(300),
            Map.of("sub", "subject", "email", "mail@example.invalid", "email_verified", true));
    var input =
        new OidcUserRequest(
            registration,
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "token",
                now,
                now.plusSeconds(300),
                Set.of("openid")),
            token);
    assertThatThrownBy(() -> new GoogleOidcUserService(accounts, provider).loadUser(input))
        .isInstanceOfSatisfying(
            OAuth2AuthenticationException.class,
            e -> {
              assertThat(e.getError().getErrorCode()).isEqualTo("account_storage_unavailable");
              assertThat(e.getMessage()).isNull();
              assertThat(e.getCause()).isNull();
            });
  }
}
