package com.stelody.user.service.ledger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class AgeEncryption {
  private final String binary;
  private final String recipient;

  public AgeEncryption(String binary, String recipient) {
    this.binary = binary;
    this.recipient = recipient;
  }

  public byte[] encrypt(byte[] plain) {
    if (plain.length > 1024)
      throw new IllegalArgumentException("Withdrawal record exceeds size limit");
    Process process = null;
    try {
      var builder = new ProcessBuilder(binary, "--encrypt", "--recipient", recipient);
      // The child needs only a public recipient; do not inherit DB/B2/Google credentials.
      builder.environment().clear();
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      process = builder.start();
      try (var input = process.getOutputStream()) {
        input.write(plain);
      }
      if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0)
        throw new IllegalStateException("Withdrawal encryption unavailable");
      byte[] result;
      try (var output = process.getInputStream()) {
        result = output.readNBytes(8193);
      }
      if (result.length == 0 || result.length > 8192)
        throw new IllegalStateException("Invalid withdrawal ciphertext");
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Withdrawal encryption interrupted");
    } catch (IOException e) {
      throw new IllegalStateException("Withdrawal encryption unavailable");
    } finally {
      if (process != null) process.destroyForcibly();
    }
  }
}
