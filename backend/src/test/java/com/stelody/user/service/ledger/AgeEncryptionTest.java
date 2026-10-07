package com.stelody.user.service.ledger;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgeEncryptionTest {
  @TempDir Path directory;

  @Test
  void realAgeEncryptsOnlyForTheConfiguredRecoveryKeyAndRejectsTampering() throws Exception {
    String binary = System.getenv().getOrDefault("AGE_BIN", "/usr/bin/age");
    Path keygen = Path.of(binary).resolveSibling("age-keygen");
    Path identity = directory.resolve("identity");
    var keys =
        new ProcessBuilder(keygen.toString(), "-o", identity.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertThat(keys.waitFor(3, TimeUnit.SECONDS)).isTrue();
    assertThat(keys.exitValue()).isZero();
    var publicKey = new ProcessBuilder(keygen.toString(), "-y", identity.toString()).start();
    String recipient =
        new String(publicKey.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertThat(publicKey.waitFor(3, TimeUnit.SECONDS)).isTrue();
    byte[] plain =
        "{\"userId\":\"00000000-0000-0000-0000-000000000001\"}".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = new AgeEncryption(binary, recipient).encrypt(plain);
    assertThat(new String(encrypted, StandardCharsets.UTF_8))
        .doesNotContain("userId", "00000000-0000");
    Path cipher = directory.resolve("record.age"), recovered = directory.resolve("record.json");
    Files.write(cipher, encrypted);
    var decrypt =
        new ProcessBuilder(
                binary,
                "-d",
                "-i",
                identity.toString(),
                "-o",
                recovered.toString(),
                cipher.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertThat(decrypt.waitFor(3, TimeUnit.SECONDS)).isTrue();
    assertThat(decrypt.exitValue()).isZero();
    assertThat(Files.readAllBytes(recovered)).containsExactly(plain);
    encrypted[encrypted.length - 1] ^= 1;
    Files.write(cipher, encrypted);
    Files.delete(recovered);
    var tampered =
        new ProcessBuilder(
                binary,
                "-d",
                "-i",
                identity.toString(),
                "-o",
                recovered.toString(),
                cipher.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertThat(tampered.waitFor(3, TimeUnit.SECONDS)).isTrue();
    assertThat(tampered.exitValue()).isNotZero();
  }

  @Test
  void oversizedPlaintextIsRejectedBeforeLaunchingAnyProcess() {
    assertThatThrownBy(() -> new AgeEncryption("/missing/age", "public").encrypt(new byte[1025]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Withdrawal record exceeds size limit");
  }

  @Test
  void unavailableExecutableFailsWithoutExposingInput() {
    assertThatThrownBy(
            () -> new AgeEncryption("/missing/age", "public").encrypt("private".getBytes()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Withdrawal encryption unavailable");
  }
}
