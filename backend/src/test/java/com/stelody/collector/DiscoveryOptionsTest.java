package com.stelody.collector;

import static org.assertj.core.api.Assertions.*;

import com.stelody.collector.config.CollectorConfiguration;
import com.stelody.collector.config.DiscoveryOptions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.mock.env.MockEnvironment;

class DiscoveryOptionsTest {
  @Test
  void defaultsAndBoundedBackfillRequireExplicitChannel() {
    var normal =
        DiscoveryOptions.from(new MockEnvironment(), new DefaultApplicationArguments("--discover"));
    assertThat(normal).isEqualTo(new DiscoveryOptions(false, null, false, 3));
    var id = UUID.randomUUID();
    var history =
        DiscoveryOptions.from(
            new MockEnvironment(),
            new DefaultApplicationArguments("--backfill", "--channel=" + id));
    assertThat(history).isEqualTo(new DiscoveryOptions(false, id, true, 1));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "--backfill",
        "--channel=bad",
        "--channel",
        "--max-pages=0",
        "--max-pages=6",
        "--max-pages=no",
        "--max-pages"
      })
  void invalidOptionsFailBeforeDatabaseAccess(String argument) {
    assertThatThrownBy(
            () ->
                DiscoveryOptions.from(
                    new MockEnvironment(), new DefaultApplicationArguments(argument)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void autoPublicationRequiresExplicitSeparatePolicyFlag() {
    var options =
        DiscoveryOptions.from(
            new MockEnvironment().withProperty("COVER_AUTO_PUBLICATION_POLICY_ALLOWED", "true"),
            new DefaultApplicationArguments("--discover"));
    assertThat(options.autoPublicationPolicyAllowed()).isTrue();
    assertThat(options.classificationAllowed()).isFalse();
  }

  @Test
  void disabledDiscoveryNeedsNoCredentialsDatabaseOrServer() {
    var application = new SpringApplication(CollectorConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles("collector");
    try (var context =
        application.run(
            "--stelody.collector.enabled=true", "--DISCOVERY_ENABLED=false", "--discover")) {
      assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
      assertThat(context.getBeansOfType(jakarta.persistence.EntityManagerFactory.class)).isEmpty();
      assertThat(context.getBeansOfType(org.flywaydb.core.Flyway.class)).isEmpty();
      assertThat(SpringApplication.exit(context)).isZero();
    }
  }
}
