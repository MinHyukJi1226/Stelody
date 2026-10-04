package com.stelody.config;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class RenderConfigurationTest {
  @Test
  void usesTheAssignedRenderHostnameForGoogleCallback() throws IOException {
    try (var context =
        context(Map.of("RENDER_EXTERNAL_URL", "https://assigned-name.onrender.com"))) {
      assertThat(callback(context))
          .isEqualTo("https://assigned-name.onrender.com/api/v1/auth/callback/google");
    }
  }

  @Test
  void explicitCustomDomainCallbackTakesPrecedence() throws IOException {
    try (var context =
        context(
            Map.of(
                "RENDER_EXTERNAL_URL", "https://assigned-name.onrender.com",
                "GOOGLE_REDIRECT_URI",
                    "https://api.stelody.example/api/v1/auth/callback/google"))) {
      assertThat(callback(context))
          .isEqualTo("https://api.stelody.example/api/v1/auth/callback/google");
    }
  }

  @Test
  void refusesAnUnencryptedExternalCallback() {
    assertThatThrownBy(
            () -> context(Map.of("RENDER_EXTERNAL_URL", "http://assigned-name.onrender.com")))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingAssignedUrlDoesNotSilentlySelectAnotherCallback() {
    assertThatThrownBy(() -> context(Map.of())).hasMessageContaining("googleClients");
  }

  private String callback(AnnotationConfigApplicationContext context) {
    return context
        .getBean(ClientRegistrationRepository.class)
        .findByRegistrationId("google")
        .getRedirectUri();
  }

  private AnnotationConfigApplicationContext context(Map<String, Object> overrides)
      throws IOException {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var values = new HashMap<String, Object>();
    values.put("GOOGLE_CLIENT_ID", "test-client");
    values.put("GOOGLE_CLIENT_SECRET", "test-secret");
    values.putAll(overrides);
    environment.getPropertySources().addFirst(new MapPropertySource("test", values));
    for (var source :
        new YamlPropertySourceLoader()
            .load("render", new ClassPathResource("application-render.yml"))) {
      environment.getPropertySources().addLast(source);
    }
    environment.setActiveProfiles("prod", "google", "render");
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment);
    context.register(PropertySourcesPlaceholderConfigurer.class, GoogleClientConfiguration.class);
    try {
      context.refresh();
      return context;
    } catch (RuntimeException failure) {
      context.close();
      throw failure;
    }
  }
}
