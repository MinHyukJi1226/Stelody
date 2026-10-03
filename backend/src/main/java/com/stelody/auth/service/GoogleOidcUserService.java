package com.stelody.auth.service;

import com.stelody.auth.domain.ReauthenticationContext;
import com.stelody.user.dto.CurrentUser;
import com.stelody.user.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
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
  private final ObjectProvider<HttpServletRequest> requests;

  public GoogleOidcUserService(
      AccountService accounts, ObjectProvider<HttpServletRequest> requests) {
    this.accounts = accounts;
    this.requests = requests;
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
      ReauthenticationContext context =
          (ReauthenticationContext)
              requests.getObject().getAttribute(ReauthenticationContext.ATTRIBUTE);
      var account =
          context == null
              ? accounts.signIn(google.getSubject(), google.getEmail())
              : accounts.reauthenticate(context.user().id(), google.getSubject());
      return new LocalOidcUser(google, account, context);
    } catch (DataAccessException exception) {
      throw new OAuth2AuthenticationException("account_storage_unavailable");
    }
  }

  public static class LocalOidcUser extends DefaultOidcUser {
    private final CurrentUser account;
    private final ReauthenticationContext reauthentication;

    LocalOidcUser(OidcUser google, CurrentUser account, ReauthenticationContext reauthentication) {
      super(List.of(new SimpleGrantedAuthority("ROLE_" + account.role())), google.getIdToken());
      this.account = account;
      this.reauthentication = reauthentication;
    }

    public CurrentUser account() {
      return account;
    }

    public ReauthenticationContext reauthentication() {
      return reauthentication;
    }
  }
}
