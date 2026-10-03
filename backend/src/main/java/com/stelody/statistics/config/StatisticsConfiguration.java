package com.stelody.statistics.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StatisticsConfiguration {
  @Bean
  Clock statisticsClock() {
    return Clock.systemUTC();
  }
}
