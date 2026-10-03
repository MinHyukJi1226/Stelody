package com.stelody.export.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ExportSettingsTest {
  @Test
  void disabledFeatureRequiresNoOAuthOrTokenKey() {
    assertThat(new ExportSettings(new MockEnvironment()).enabled()).isFalse();
  }

  @Test
  void localCallbackAndTestProviderAreAllowedOnlyInTheirProfiles() {
    var env =
        new MockEnvironment()
            .withProperty("stelody.export.enabled", "true")
            .withProperty(
                "stelody.export.redirect-uri", "http://localhost:8080/api/v1/me/youtube/callback");
    env.setActiveProfiles("local");
    assertThat(new ExportSettings(env).enabled()).isTrue();
    env.setActiveProfiles("prod", "local");
    assertThatThrownBy(() -> new ExportSettings(env)).isInstanceOf(IllegalArgumentException.class);
    env.setActiveProfiles("local");
    env.setProperty("stelody.export.api-url", "http://localhost:8090/youtube/v3");
    assertThatThrownBy(() -> new ExportSettings(env)).isInstanceOf(IllegalArgumentException.class);
    env.setActiveProfiles("test");
    assertThat(new ExportSettings(env).enabled()).isTrue();
  }
}
