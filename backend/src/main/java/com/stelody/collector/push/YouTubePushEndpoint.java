package com.stelody.collector.push;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public record YouTubePushEndpoint(
    YouTubePushSettings settings,
    YouTubePushSubscriptions subscriptions,
    YouTubePushProcessor processor) {
  public boolean signature(String channel, byte[] body, String signature) {
    if (signature == null || !signature.matches("(?:sha1=[a-fA-F0-9]{40}|sha256=[a-fA-F0-9]{64})"))
      return false;
    try {
      String algorithm = signature.startsWith("sha1=") ? "HmacSHA1" : "HmacSHA256";
      var mac = Mac.getInstance(algorithm);
      mac.init(
          new SecretKeySpec(
              settings.deliverySecret(channel).getBytes(StandardCharsets.UTF_8), algorithm));
      return MessageDigest.isEqual(
          mac.doFinal(body),
          HexFormat.of().parseHex(signature.substring(signature.indexOf('=') + 1)));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  @Override
  public String toString() {
    return "YouTubePushEndpoint[configured]";
  }
}
