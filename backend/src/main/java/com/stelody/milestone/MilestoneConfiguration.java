package com.stelody.milestone;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.service.CollectorExecution;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("!collector")
@ConditionalOnProperty(
    name = {
      "stelody.milestones.enabled",
      "stelody.collector.enabled",
      "stelody.collector.scheduled-enabled"
    },
    havingValue = "true")
public class MilestoneConfiguration {
  @Bean(defaultCandidate = false)
  ThreadPoolTaskScheduler milestoneTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("milestone-");
    return scheduler;
  }

  @Bean(destroyMethod = "close")
  MilestoneWorker milestoneWorker(Environment env) {
    var settings = CollectorSettings.from(env);
    return new MilestoneWorker(
        CollectorExecution.createDataSource(settings),
        settings,
        Clock.systemUTC(),
        d -> Thread.sleep(d));
  }
}
