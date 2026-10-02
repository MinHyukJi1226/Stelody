package com.stelody.admin.command;

import com.zaxxer.hikari.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Explicit operator context: no component scan, server, Google OAuth, Flyway or collector. */
@Configuration(proxyBeanMethods = false)
@Profile("operator")
public class AdminAccountConfiguration {
  @Bean
  OperatorRunner operatorRunner(Environment environment) {
    return new OperatorRunner(environment);
  }

  public static class OperatorRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(OperatorRunner.class);
    private final Environment env;
    private int exit;

    OperatorRunner(Environment env) {
      this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
      try {
        if (!args.getNonOptionArgs().isEmpty()
            || !Set.of("user-id", "role", "reason").equals(args.getOptionNames()))
          throw new IllegalArgumentException();
        UUID id = UUID.fromString(option(args, "user-id"));
        String role = option(args, "role"), reason = option(args, "reason");
        if (!Set.of("ADMIN", "USER").contains(role) || reason.isBlank() || reason.length() > 500)
          throw new IllegalArgumentException();
        var config = new HikariConfig();
        config.setJdbcUrl(required("ADMIN_DB_URL"));
        config.setUsername(required("ADMIN_DB_USERNAME"));
        config.setPassword(required("ADMIN_DB_PASSWORD"));
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", "5");
        config.addDataSourceProperty("socketTimeout", "10");
        try (var source = new HikariDataSource(config)) {
          boolean changed = new AdminAccountCommand(source).change(id, role, reason);
          LOG.info(
              "admin-account status={} accountId={} role={}",
              changed ? "CHANGED" : "UNCHANGED",
              id,
              role);
        }
      } catch (RuntimeException e) {
        exit = 1;
        LOG.error("admin-account status=FAILED code=ADMIN_ACCOUNT_COMMAND_FAILED");
      }
    }

    private String required(String key) {
      String value = env.getProperty(key);
      if (value == null || value.isBlank()) throw new IllegalArgumentException();
      return value;
    }

    private String option(ApplicationArguments args, String name) {
      var values = args.getOptionValues(name);
      if (values == null || values.size() != 1) throw new IllegalArgumentException();
      return values.getFirst();
    }

    @Override
    public int getExitCode() {
      return exit;
    }
  }

  public static int launch(String[] args) {
    var app = new SpringApplication(AdminAccountConfiguration.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setAdditionalProfiles("operator");
    app.setRegisterShutdownHook(false);
    app.setLogStartupInfo(false);
    try (var context = app.run(args)) {
      return SpringApplication.exit(context);
    }
  }
}
