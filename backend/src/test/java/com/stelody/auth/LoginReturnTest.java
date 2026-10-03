package com.stelody.auth;

import static org.assertj.core.api.Assertions.*;

import com.stelody.auth.domain.LoginReturn;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoginReturnTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/",
        "/songs/abc",
        "/songs?q=%ED%95%9C%EA%B8%80&member=one&member=two#results",
        "/members/%EB%A6%B0"
      })
  void internalScreenPathQueryAndFragmentArePreserved(String path) {
    assertThat(LoginReturn.validate(path)).isEqualTo(path);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "songs",
        "https://evil.example",
        "//evil.example",
        "/\\evil.example",
        "/%2f%2fevil.example",
        "/%5cevil.example",
        "/%252fevil.example",
        "/songs/../api/v1/auth/google",
        "/songs/%2E%2E/api/v1/auth/google",
        "/api",
        "/api/v1/auth/google",
        "/%61pi/v1/auth/google",
        "/actuator/health",
        "/songs;param=one",
        "/songs/%00",
        "/songs/%0d%0aLocation:evil",
        "/songs q",
        "/songs\n",
        "/songs?query=\r",
        "/songs/%",
        "/songs/%GG"
      })
  void externalAmbiguousAndBackendPathsAreRejected(String path) {
    assertThatThrownBy(() -> LoginReturn.validate(path))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unicodeIsEncodedAndLengthIsBounded() {
    assertThat(LoginReturn.validate("/members/린?q=노래"))
        .isEqualTo("/members/%EB%A6%B0?q=%EB%85%B8%EB%9E%98");
    assertThatThrownBy(() -> LoginReturn.validate(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LoginReturn.validate("/" + "a".repeat(2048)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(LoginReturn.validate("/" + "a".repeat(2047))).hasSize(2048);
    assertThatThrownBy(() -> LoginReturn.validate("/" + "린".repeat(230)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resultReplacesCallerProvidedFlagWithoutDroppingQueryOrFragment() {
    var target =
        new LoginReturn(
            "/songs?q=%ED%95%9C&loginResult=success&member=a&member=b#results", Instant.now());
    assertThat(target.location("cancelled"))
        .isEqualTo("/songs?q=%ED%95%9C&member=a&member=b&loginResult=cancelled#results");
  }

  @Test
  void encodedResultNamesCannotOverrideServerResult() {
    var target =
        new LoginReturn(
            "/songs?%6CoginResult=success&member=a&login%52esult=success&loginResult=success#results",
            Instant.now());
    assertThat(target.location("failed")).isEqualTo("/songs?member=a&loginResult=failed#results");
  }
}
