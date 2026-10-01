package com.stelody.collector.config;

import com.stelody.collector.service.VideoCollector;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    return new CollectorRunner(CollectorSettings.from(environment));
  }

  public static final class CollectorRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(CollectorRunner.class);
    private final CollectorSettings settings;
    private int exitCode;

    CollectorRunner(CollectorSettings settings) {
      this.settings = settings;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments arguments) {
      if (!settings.enabled()) {
        LOG.info("collector status=SKIPPED_DISABLED");
        return;
      }
      try {
        settings.validate();
        var config = new HikariConfig();
        config.setJdbcUrl(settings.dbUrl());
        config.setUsername(settings.dbUsername());
        config.setPassword(settings.dbPassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", "5");
        config.addDataSourceProperty("socketTimeout", "10");
        try (var source = new HikariDataSource(config)) {
          var collector =
              new VideoCollector(
                  source, settings, Clock.systemUTC(), duration -> Thread.sleep(duration));
          var result = collector.collect();
          exitCode = result.exitCode();
          LOG.info(
              "collector runId={} status={} code={}",
              result.runId(),
              result.status(),
              result.code());
        }
      } catch (RuntimeException exception) {
        exitCode = 1;
        // Configuration and outbound exceptions can contain credentials or API URLs.
        LOG.error("collector status=FAILED code=COLLECTOR_SETUP_FAILED");
      }
    }

    @Override
    public int getExitCode() {
      return exitCode;
    }
  }
}
