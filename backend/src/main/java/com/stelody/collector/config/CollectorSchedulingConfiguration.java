package com.stelody.collector.config;

import com.stelody.collector.scheduling.CollectionScheduleState;
import com.stelody.collector.scheduling.ScheduledCollector;
import com.stelody.collector.service.CollectorExecution;
import java.time.Clock;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("!collector")
@ConditionalOnProperty(
    name = {"stelody.collector.scheduled-enabled", "stelody.collector.enabled"},
    havingValue = "true")
public class CollectorSchedulingConfiguration {
  // Excluded from default scheduler selection: collection must not block export / auth cleanup.
  @Bean(defaultCandidate = false)
  ThreadPoolTaskScheduler collectorTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("collector-");
    return scheduler;
  }

  @Bean(destroyMethod = "close")
  ScheduledCollector scheduledCollector(Environment environment) {
    var settings = CollectorSettings.from(environment);
    // Deliberately not a DataSource bean: Spring/JPA/session continue using the web role.
    var source = CollectorExecution.createDataSource(settings);
    var state =
        new CollectionScheduleState(
            source,
            environment.getProperty("DISCOVERY_ENABLED", Boolean.class, false),
            environment.getProperty("stelody.collection.retry-enabled", Boolean.class, false));
    var execution = new CollectorExecution(settings, environment);
    return new ScheduledCollector(
        Clock.systemUTC(),
        state::due,
        plan ->
            execution.execute(
                source,
                new DefaultApplicationArguments(
                    plan.discovery() ? new String[] {"--discover"} : new String[] {}),
                plan.videos()),
        source);
  }
}
