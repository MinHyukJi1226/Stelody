package com.stelody.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stelody.auth.domain.LoginReturn;
import com.stelody.auth.service.ReauthenticationService;
import com.stelody.auth.web.ReauthenticationRequests;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class LoginReturnRepositoryTest {
  final ReauthenticationRequests repository =
      new ReauthenticationRequests(mock(ReauthenticationService.class));
  final MockHttpServletResponse response = new MockHttpServletResponse();

  OAuth2AuthorizationRequest authorization(String state) {
    return OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://accounts.example.invalid/authorize")
        .clientId("test-client")
        .redirectUri("http://localhost/api/v1/auth/callback/google")
        .state(state)
        .attributes(Map.of("registration_id", "google"))
        .build();
  }

  MockHttpServletRequest start(String state, Instant expires) {
    var request = new MockHttpServletRequest();
    request.setAttribute(LoginReturn.ATTRIBUTE, new LoginReturn("/songs?q=cover", expires));
    repository.saveAuthorizationRequest(authorization(state), request, response);
    return request;
  }

  MockHttpServletRequest callback(MockHttpServletRequest start, String state) {
    var callback = new MockHttpServletRequest();
    callback.setSession(start.getSession());
    callback.addParameter("state", state);
    callback.addParameter("returnTo", "https://evil.example");
    return callback;
  }

  @Test
  void matchedStateRestoresOnlyServerStoredTargetAndConsumesIt() {
    var start = start("expected", Instant.now().plusSeconds(300));
    var callback = callback(start, "expected");
    var stored = repository.removeAuthorizationRequest(callback, response);
    assertThat(stored.getAuthorizationRequestUri()).doesNotContain("returnTo", "cover");
    assertThat(((LoginReturn) callback.getAttribute(LoginReturn.ATTRIBUTE)).path())
        .isEqualTo("/songs?q=cover");
    var replay = callback(start, "expected");
    assertThat(repository.removeAuthorizationRequest(replay, response)).isNull();
    assertThat(replay.getAttribute(LoginReturn.ATTRIBUTE)).isNull();
  }

  @Test
  void wrongStateAndDifferentSessionNeverRestoreTargetOrConsumeMatchingRequest() {
    var start = start("expected", Instant.now().plusSeconds(300));
    var wrong = callback(start, "wrong");
    assertThat(repository.removeAuthorizationRequest(wrong, response)).isNull();
    assertThat(wrong.getAttribute(LoginReturn.ATTRIBUTE)).isNull();
    var other = new MockHttpServletRequest();
    other.addParameter("state", "expected");
    assertThat(repository.removeAuthorizationRequest(other, response)).isNull();
    assertThat(other.getAttribute(LoginReturn.ATTRIBUTE)).isNull();
    assertThat(repository.removeAuthorizationRequest(callback(start, "expected"), response))
        .isNotNull();
  }

  @Test
  void expiryRejectsAndConsumesRequestWithoutRestoringTarget() {
    var start = start("expected", Instant.now().minusSeconds(1));
    var callback = callback(start, "expected");
    assertThatThrownBy(() -> repository.removeAuthorizationRequest(callback, response))
        .isInstanceOf(OAuth2AuthenticationException.class);
    assertThat(callback.getAttribute(LoginReturn.ATTRIBUTE)).isNull();
    assertThat(repository.removeAuthorizationRequest(callback(start, "expected"), response))
        .isNull();
  }

  @Test
  void newLoginWithoutTargetDoesNotReuseEarlierReturnPath() {
    var start = start("old", Instant.now().plusSeconds(300));
    var next = new MockHttpServletRequest();
    next.setSession(start.getSession());
    repository.saveAuthorizationRequest(authorization("new"), next, response);
    var callback = callback(next, "new");
    assertThat(repository.removeAuthorizationRequest(callback, response)).isNotNull();
    assertThat(callback.getAttribute(LoginReturn.ATTRIBUTE)).isNull();
  }
}
