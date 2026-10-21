package com.stelody.export;

import static org.assertj.core.api.Assertions.*;

import com.stelody.export.config.ExportSettings;
import com.stelody.export.web.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class YouTubeOAuthReturnTest {
  @Test
  void disabledLocalClientAndDefaultReturnAreExplicit() {
    var settings = new ExportSettings(new MockEnvironment());
    assertThat(settings.callbackClient(null)).isEqualTo("default");
    assertThatThrownBy(() -> settings.callbackClient("local")).isInstanceOf(ExportException.class);
    var target = new YouTubeOAuthReturn(settings);
    assertThat(target.location("default", null, "success", null))
        .isEqualTo("/library/youtube?youtubeResult=success");
    assertThat(target.location("local", null, "failed", "INVALID_YOUTUBE_CLIENT"))
        .isEqualTo("/library/youtube?youtubeResult=failed&youtubeError=INVALID_YOUTUBE_CLIENT");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://evil.example/api/v1/me/youtube/callback/local",
        "http://localhost.evil.example:5173/api/v1/me/youtube/callback/local",
        "http://user@localhost:5173/api/v1/me/youtube/callback/local",
        "http://localhost:5173/api/v1/me/youtube/callback/local?next=1",
        "http://localhost:5173/api/v1/me/youtube/callback/local#next",
        "http://localhost:5173/api/v1/me/youtube/callback",
        "http://localhost:0/api/v1/me/youtube/callback/local",
        "http://localhost:65536/api/v1/me/youtube/callback/local"
      })
  void rejectsUntrustedLocalCallbacks(String url) {
    assertThatThrownBy(
            () ->
                new ExportSettings(
                    new MockEnvironment().withProperty("stelody.export.local-redirect-uri", url)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://frontend.example",
        "//frontend.example",
        "https://user@frontend.example",
        "https://frontend.example/path",
        "https://frontend.example?x=1",
        "https://frontend.example#next",
        "https://frontend.example:0",
        "https://frontend.example:65536"
      })
  void rejectsUntrustedFrontendOrigins(String url) {
    assertThatThrownBy(
            () ->
                new ExportSettings(
                    new MockEnvironment().withProperty("stelody.export.frontend-origin", url)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void prodSettingsStillAllowExplicitLocalProxyWithoutChangingCookieConfiguration() {
    var env =
        new MockEnvironment()
            .withProperty("stelody.export.enabled", "true")
            .withProperty(
                "stelody.export.redirect-uri", "https://api.example/api/v1/me/youtube/callback")
            .withProperty(
                "stelody.export.local-redirect-uri",
                "http://localhost:5173/api/v1/me/youtube/callback/local")
            .withProperty("stelody.export.frontend-origin", "https://frontend.example");
    env.setActiveProfiles("prod");
    var settings = new ExportSettings(env);
    assertThat(settings.callbackUri("default"))
        .isEqualTo("https://api.example/api/v1/me/youtube/callback");
    assertThat(settings.callbackUri("local"))
        .isEqualTo("http://localhost:5173/api/v1/me/youtube/callback/local");
    var returns = new YouTubeOAuthReturn(settings);
    assertThat(returns.location("default", "/library/youtube", "success", null))
        .isEqualTo("https://frontend.example/library/youtube?youtubeResult=success");
    assertThat(returns.location("local", "/library/youtube", "success", null))
        .isEqualTo("http://localhost:5173/library/youtube?youtubeResult=success");
  }
}
