package com.stelody;

import com.stelody.admin.command.AdminAccountConfiguration;
import com.stelody.collector.CollectorLauncher;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StelodyApplication {

  public static void main(String[] args) {
    if (Arrays.asList(args).contains("--admin-account")) {
      if (Arrays.asList(args).contains("--collector"))
        throw new IllegalArgumentException("Choose one command");
      System.exit(
          AdminAccountConfiguration.launch(
              Arrays.stream(args)
                  .filter(arg -> !arg.equals("--admin-account"))
                  .toArray(String[]::new)));
    } else if (Arrays.asList(args).contains("--collector")) {
      String[] collectorArgs =
          Arrays.stream(args).filter(arg -> !arg.equals("--collector")).toArray(String[]::new);
      System.exit(CollectorLauncher.run(collectorArgs));
    } else {
      SpringApplication.run(StelodyApplication.class, args);
    }
  }
}
