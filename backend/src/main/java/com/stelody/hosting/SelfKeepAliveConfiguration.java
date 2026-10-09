package com.stelody.hosting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("render & !collector")
@ConditionalOnProperty(name = "stelody.hosting.self-keep-alive-enabled", havingValue = "true")
public class SelfKeepAliveConfiguration {
  @Bean(defaultCandidate = false)
  ThreadPoolTaskScheduler selfKeepAliveTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("self-keep-alive-");
    return scheduler;
  }

  @Bean(destroyMethod = "close")
  SelfKeepAlive selfKeepAlive(Environment environment) {
    return new SelfKeepAlive(environment.getProperty("RENDER_EXTERNAL_URL", ""));
  }
}
