package com.stelody.collector.config;

import com.stelody.collector.service.CollectorExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * An explicit context: no component scan, HTTP server, JPA, session, security or Flyway startup.
 */
@Configuration(proxyBeanMethods = false)
@Profile("collector")
public class CollectorConfiguration {
  @Bean
  CollectorRunner collectorRunner(Environment environment) {
    return new CollectorRunner(CollectorSettings.from(environment), environment);
  }

  public static final class CollectorRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(CollectorRunner.class);
    private final CollectorSettings settings;
    private final Environment environment;
    private int exitCode;

    CollectorRunner(CollectorSettings settings, Environment environment) {
      this.settings = settings;
      this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
      if (!settings.enabled()) {
        LOG.info("collector status=SKIPPED_DISABLED");
        return;
      }
      if (arguments.containsOption("discover")
          && !environment.getProperty("DISCOVERY_ENABLED", Boolean.class, false)) {
        LOG.info("discovery status=SKIPPED_DISABLED");
        return;
      }
      try (var source = CollectorExecution.createDataSource(settings)) {
        exitCode = new CollectorExecution(settings, environment).execute(source, arguments);
      } catch (RuntimeException exception) {
        exitCode = 1;
        LOG.error("collector status=FAILED code=COLLECTOR_SETUP_FAILED");
      }
    }

    @Override
    public int getExitCode() {
      return exitCode;
    }
  }
}
