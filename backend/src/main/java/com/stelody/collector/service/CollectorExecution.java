package com.stelody.collector.service;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.config.DiscoveryOptions;
import com.stelody.collector.domain.CollectionBudget;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;

/** Shared execution for the one-shot CLI and the web server's dedicated scheduler. */
public final class CollectorExecution {
  private static final Logger LOG = LoggerFactory.getLogger(CollectorExecution.class);
  private final CollectorSettings settings;
  private final Environment environment;

  public CollectorExecution(CollectorSettings settings, Environment environment) {
    this.settings = settings;
    this.environment = environment;
  }

  public static HikariDataSource createDataSource(CollectorSettings settings) {
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
    return new HikariDataSource(config);
  }

  public int execute(DataSource source, ApplicationArguments arguments) {
    return execute(source, arguments, true);
  }

  public int execute(DataSource source, ApplicationArguments arguments, boolean collectViews) {
    int exitCode = 0;
    if (!settings.enabled()) {
      LOG.info("collector status=SKIPPED_DISABLED");
      return 0;
    }
    try {
      boolean discover = arguments.containsOption("discover");
      if (discover && !environment.getProperty("DISCOVERY_ENABLED", Boolean.class, false)) {
        LOG.info("discovery status=SKIPPED_DISABLED");
        return 0;
      }
      settings.validate();
      var options = discover ? DiscoveryOptions.from(environment, arguments) : null;
      var budget = new CollectionBudget(settings.maxRuntime());
      if (environment.getProperty("stelody.collection.retry-enabled", Boolean.class, false)) {
        var retried =
            new CollectionRetryWorker(
                    source, settings, Clock.systemUTC(), duration -> Thread.sleep(duration))
                .collect(
                    budget,
                    environment.getProperty("DISCOVERY_ENABLED", Boolean.class, false),
                    environment.getProperty(
                        "DISCOVERY_CLASSIFICATION_ALLOWED", Boolean.class, false),
                    environment.getProperty(
                        "COVER_AUTO_PUBLICATION_POLICY_ALLOWED", Boolean.class, false));
        if (retried.isPresent()) {
          var result = retried.get();
          exitCode = result.exitCode();
          LOG.info(
              "collector retry runId={} status={} code={}",
              result.runId(),
              result.status(),
              result.code());
          return exitCode;
        }
      }
      var collector =
          new VideoCollector(
              source, settings, Clock.systemUTC(), duration -> Thread.sleep(duration));
      var result =
          !collectViews || (options != null && options.backfill())
              ? null
              : collector.collect(budget);
      if (result != null) {
        exitCode = result.exitCode();
        LOG.info(
            "collector runId={} status={} code={}", result.runId(), result.status(), result.code());
      }
      if (discover
          && (result == null
              || (result.exitCode() == 0 && !result.status().equals("SKIPPED_LOCKED")))) {
        var discovered =
            new DiscoveryCollector(
                    source,
                    settings,
                    options,
                    Clock.systemUTC(),
                    duration -> Thread.sleep(duration))
                .collect(budget);
        exitCode = discovered.exitCode();
        LOG.info(
            "discovery runId={} status={} code={}",
            discovered.runId(),
            discovered.status(),
            discovered.code());
      }
    } catch (RuntimeException exception) {
      exitCode = 1;
      // Configuration and outbound exceptions can contain credentials or API URLs.
      LOG.error("collector status=FAILED code=COLLECTOR_SETUP_FAILED");
    }
    return exitCode;
  }
}
