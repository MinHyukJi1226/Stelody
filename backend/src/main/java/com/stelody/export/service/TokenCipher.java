package com.stelody.export.service;

import com.stelody.export.config.ExportSettings;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class TokenCipher {
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  @org.springframework.beans.factory.annotation.Autowired
  public TokenCipher(ExportSettings settings) {
    this(settings.key().isBlank() && !settings.enabled() ? null : settings.key());
  }

  TokenCipher(String encodedKey) {
    if (encodedKey == null) {
      key = null;
      return;
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(encodedKey);
      if (bytes.length != 32) throw new IllegalArgumentException();
      key = new SecretKeySpec(bytes, "AES");
      Arrays.fill(bytes, (byte) 0);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Configure a base64-encoded 32-byte YouTube token encryption key");
    }
  }

  public String encrypt(UUID user, String purpose, String value) {
    if (value == null) return null;
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      var cipher = cipher(Cipher.ENCRYPT_MODE, user, purpose, nonce);
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
      System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
      return Base64.getEncoder().encodeToString(result);
    } catch (Exception e) {
      throw new IllegalStateException("Token encryption failed");
    }
  }

  public String decrypt(UUID user, String purpose, String value) {
    if (value == null) return null;
    try {
      byte[] bytes = Base64.getDecoder().decode(value);
      if (bytes.length < 28) throw new IllegalArgumentException();
      return new String(
          cipher(Cipher.DECRYPT_MODE, user, purpose, Arrays.copyOf(bytes, 12))
              .doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)),
          StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Token decryption failed");
    }
  }

  private Cipher cipher(int mode, UUID user, String purpose, byte[] nonce) throws Exception {
    if (key == null) throw new IllegalStateException();
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(128, nonce));
    cipher.updateAAD((user + ":" + purpose).getBytes(StandardCharsets.UTF_8));
    return cipher;
  }
}
