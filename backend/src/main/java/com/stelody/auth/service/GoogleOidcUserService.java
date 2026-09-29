package com.stelody.auth.service;

import com.stelody.user.dto.CurrentUser;
import com.stelody.user.service.AccountService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class GoogleOidcUserService extends OidcUserService {
  private final AccountService accounts;

  public GoogleOidcUserService(AccountService accounts) {
    this.accounts = accounts;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    // Spring's provider has already validated signature, issuer, audience, expiry and nonce.
    var google = super.loadUser(request);
    if (!"google".equals(request.getClientRegistration().getRegistrationId())
        || !Boolean.TRUE.equals(google.getEmailVerified())) {
      throw new OAuth2AuthenticationException("invalid_identity");
    }
    try {
      return new LocalOidcUser(google, accounts.signIn(google.getSubject(), google.getEmail()));
    } catch (DataAccessException exception) {
      throw new OAuth2AuthenticationException("account_storage_unavailable");
    }
  }

  public static class LocalOidcUser extends DefaultOidcUser {
    private final CurrentUser account;

    LocalOidcUser(OidcUser google, CurrentUser account) {
      super(List.of(new SimpleGrantedAuthority("ROLE_" + account.role())), google.getIdToken());
      this.account = account;
    }

    public CurrentUser account() {
      return account;
    }
  }
}
