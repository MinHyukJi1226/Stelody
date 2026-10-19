package com.stelody.auth;

import static org.assertj.core.api.Assertions.*;

import com.stelody.auth.web.GoogleLoginClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoogleLoginClientsTest {
  @Test
  void localClientIsOptInAndCannotAcceptClientSuppliedUrls() {
    var disabled = new GoogleLoginClients("");
    assertThat(disabled.select(null)).isNull();
    assertThatThrownBy(() -> disabled.select(new String[] {"local"}))
        .isInstanceOf(IllegalArgumentException.class);
    var enabled = new GoogleLoginClients("http://localhost:5173/api/v1/auth/callback/google");
    assertThat(enabled.select(null)).isNull();
    var local = enabled.select(new String[] {"local"});
    assertThat(local.location("/login?returnTo=%2Flibrary&loginResult=success"))
        .isEqualTo("http://localhost:5173/login?returnTo=%2Flibrary&loginResult=success");
    for (String[] values :
        new String[][] {
          {""}, {"other"}, {"https://evil.example"}, {"local", "local"}, {"local", "other"}
        })
      assertThatThrownBy(() -> enabled.select(values)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://evil.example/api/v1/auth/callback/google",
        "http://localhost.evil.example/api/v1/auth/callback/google",
        "http://user@localhost:5173/api/v1/auth/callback/google",
        "http://localhost:5173/api/v1/auth/callback/google?x=1",
        "http://localhost:5173/api/v1/auth/callback/google#x",
        "http://localhost:5173/other",
        "//localhost:5173/api/v1/auth/callback/google",
        "http://localhost:0/api/v1/auth/callback/google",
        "http://localhost:65536/api/v1/auth/callback/google",
        "http://localhost:5173/api/v1/auth/callback/%67oogle"
      })
  void rejectsUntrustedCallbackConfiguration(String value) {
    assertThatThrownBy(() -> new GoogleLoginClients(value))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
