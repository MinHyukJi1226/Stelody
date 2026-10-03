package com.stelody.export.service;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class TokenCipherTest {
  private final String key = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void encryptsWithRandomNonceAndAuthenticatesOwnerPurposeAndCiphertext() {
    var cipher = new TokenCipher(key);
    UUID owner = UUID.randomUUID();
    String plain = "test-token";
    String first = cipher.encrypt(owner, "refresh", plain),
        second = cipher.encrypt(owner, "refresh", plain);
    assertThat(first).isNotEqualTo(second).doesNotContain(plain);
    assertThat(cipher.decrypt(owner, "refresh", first)).isEqualTo(plain);
    assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), "refresh", first))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.decrypt(owner, "access", first))
        .isInstanceOf(IllegalStateException.class);
    byte[] corrupted = Base64.getDecoder().decode(first);
    corrupted[corrupted.length - 1] ^= 1;
    assertThatThrownBy(
            () -> cipher.decrypt(owner, "refresh", Base64.getEncoder().encodeToString(corrupted)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[16])))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
