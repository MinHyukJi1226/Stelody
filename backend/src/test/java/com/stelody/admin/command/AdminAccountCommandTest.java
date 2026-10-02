package com.stelody.admin.command;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class AdminAccountCommandTest {
  @Test
  void missingOperatorCredentialsCannotUseWebCredentials() {
    var env =
        new MockEnvironment()
            .withProperty("DB_URL", "jdbc:postgresql://unused.invalid/stelody")
            .withProperty("DB_USERNAME", "web")
            .withProperty("DB_PASSWORD", "unused");
    var runner = new AdminAccountConfiguration.OperatorRunner(env);
    runner.run(
        new DefaultApplicationArguments(
            "--user-id=00000000-0000-0000-0000-000000000001", "--role=ADMIN", "--reason=fixture"));
    assertThat(runner.getExitCode()).isEqualTo(1);
  }

  @Test
  void missingDuplicatedAndUnknownArgumentsFailBeforeDatabaseAccess() {
    for (String[] args :
        new String[][] {
          {"--role=ADMIN"},
          {"--user-id=invalid", "--role=ADMIN", "--reason=fixture"},
          {
            "--user-id=00000000-0000-0000-0000-000000000001",
            "--role=ADMIN",
            "--role=USER",
            "--reason=fixture"
          },
          {
            "--user-id=00000000-0000-0000-0000-000000000001",
            "--role=ADMIN",
            "--reason=fixture",
            "--collector"
          }
        }) {
      var runner = new AdminAccountConfiguration.OperatorRunner(new MockEnvironment());
      runner.run(new DefaultApplicationArguments(args));
      assertThat(runner.getExitCode()).isEqualTo(1);
    }
  }
}
