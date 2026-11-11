package com.stelody.review.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@Profile("!collector")
@ConditionalOnProperty(
    name = {"stelody.collector.enabled", "DISCOVERY_CLASSIFICATION_ALLOWED"},
    havingValue = "true")
public class AutomaticOriginalWorker {
  private static final Logger LOG = LoggerFactory.getLogger(AutomaticOriginalWorker.class);
  private final AutomaticOriginalService service;

  public AutomaticOriginalWorker(AutomaticOriginalService service) {
    this.service = service;
  }

  @Bean(defaultCandidate = false)
  static ThreadPoolTaskScheduler originalLookupScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("original-lookup-");
    return scheduler;
  }

  @Scheduled(scheduler = "originalLookupScheduler", fixedDelay = 60000, initialDelay = 60000)
  public void tick() {
    try {
      service.runNext();
    } catch (RuntimeException exception) {
      LOG.warn("Automatic original metadata could not be saved");
    }
  }
}
