package com.stelody.collector.push;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.service.CollectorExecution;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("!collector")
@ConditionalOnProperty(
    name = {"stelody.collector.push.enabled", "stelody.collector.enabled", "DISCOVERY_ENABLED"},
    havingValue = "true")
public class YouTubePushConfiguration {
  @Bean(defaultCandidate = false)
  ThreadPoolTaskScheduler youtubePushTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("youtube-push-");
    return scheduler;
  }

  @Bean
  YouTubePushSettings youtubePushSettings(
      @Value("${stelody.collector.push.origin}") URI origin,
      @Value("${stelody.collector.push.secret}") String secret) {
    return new YouTubePushSettings(origin, secret);
  }

  @Bean(destroyMethod = "close")
  YouTubePushProcessor youtubePushProcessor(Environment env) {
    var settings = CollectorSettings.from(env);
    return new YouTubePushProcessor(
        CollectorExecution.createDataSource(settings),
        settings,
        Clock.systemUTC(),
        env.getProperty("DISCOVERY_CLASSIFICATION_ALLOWED", Boolean.class, false),
        env.getProperty("COVER_AUTO_PUBLICATION_POLICY_ALLOWED", Boolean.class, false),
        d -> Thread.sleep(d));
  }

  @Bean(destroyMethod = "close")
  YouTubePushSubscriptions youtubePushSubscriptions(
      YouTubePushSettings settings, YouTubePushProcessor processor) {
    return new YouTubePushSubscriptions(
        settings,
        processor::channels,
        Clock.systemUTC(),
        URI.create("https://pubsubhubbub.appspot.com/subscribe"));
  }

  @Bean
  YouTubePushEndpoint youtubePushEndpoint(
      YouTubePushSettings settings,
      YouTubePushSubscriptions subscriptions,
      YouTubePushProcessor processor) {
    return new YouTubePushEndpoint(settings, subscriptions, processor);
  }
}
