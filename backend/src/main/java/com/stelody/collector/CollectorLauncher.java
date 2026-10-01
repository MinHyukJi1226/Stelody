package com.stelody.collector;

import com.stelody.collector.config.CollectorConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

public final class CollectorLauncher {
  private CollectorLauncher() {}

  public static int run(String[] args) {
    var application = new SpringApplication(CollectorConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles("collector");
    application.setRegisterShutdownHook(false);
    application.setLogStartupInfo(false);
    try (var context = application.run(args)) {
      return SpringApplication.exit(context);
    }
  }
}
