package com.stelody.auth;

import static org.assertj.core.api.Assertions.*;

import com.stelody.auth.domain.ReauthenticationReturn;
import com.stelody.auth.web.ReauthenticationClients;
import com.stelody.user.web.AccountException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReauthenticationClientsTest {
  @Test
  void compatibilityDefaultsAndDisabledLocalAreExplicit() {
    var c = new ReauthenticationClients("", "", "");
    assertThat(c.select(null, null)).isNull();
    assertThat(c.select(null, "default")).isNull();
    assertThatThrownBy(() -> c.select(null, "local"))
        .isInstanceOfSatisfying(
            AccountException.class,
            e -> assertThat(e.code()).isEqualTo("INVALID_REAUTHENTICATION_CLIENT"));
    assertThatThrownBy(() -> c.select("/withdraw", null))
        .isInstanceOfSatisfying(
            AccountException.class,
            e -> assertThat(e.code()).isEqualTo("GOOGLE_REAUTHENTICATION_UNAVAILABLE"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://evil.test/api/v1/auth/callback/google-reauthentication-local",
        "http://localhost:5173/api/v1/auth/callback/google",
        "http://localhost:5173/api/v1/auth/callback/google-reauthentication-local?code=x",
        "http://user@localhost:5173/api/v1/auth/callback/google-reauthentication-local"
      })
  void localConfigurationCannotChooseRemoteOrUnrelatedCallback(String uri) {
    assertThatThrownBy(() -> new ReauthenticationClients("", uri, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void returnStripsReservedParametersAndUsesOnlyTheConfiguredOrigin() {
    var c =
        new ReauthenticationClients(
            "https://api.example.test" + ReauthenticationReturn.CALLBACK,
            "http://localhost:5173" + ReauthenticationReturn.LOCAL_CALLBACK,
            "https://app.example.test");
    var target =
        c.select(
            "/withdraw?next=done&%63ode=private&ID_TOKEN=private&reauthError=fake#confirm",
            "local");
    assertThat(target.location(c.origin("local"), "success", null))
        .isEqualTo("http://localhost:5173/withdraw?next=done&reauthResult=success#confirm");
  }
}
