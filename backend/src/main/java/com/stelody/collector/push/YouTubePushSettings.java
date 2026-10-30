package com.stelody.collector.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public record YouTubePushSettings(URI origin, String secret) {
  public static final String PATH = "/api/v1/integrations/youtube/push/{channel}/{token}";

  public YouTubePushSettings {
    if (origin == null
        || !"https".equals(origin.getScheme())
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getPort() != -1
        || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
        || origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || secret == null
        || secret.getBytes(StandardCharsets.UTF_8).length < 32
        || secret.getBytes(StandardCharsets.UTF_8).length > 128)
      throw new IllegalArgumentException("YOUTUBE_PUSH_CONFIGURATION_INVALID");
  }

  public static String topic(String channel) {
    return "https://www.youtube.com/xml/feeds/videos.xml?channel_id=" + channel;
  }

  public String token(String channel) {
    return mac("callback:" + channel);
  }

  public String deliverySecret(String channel) {
    return mac("delivery:" + channel);
  }

  public String callback(String channel) {
    return origin
        .resolve("/api/v1/integrations/youtube/push/" + channel + "/" + token(channel))
        .toString();
  }

  public boolean accepts(String channel, String token) {
    return channel != null
        && channel.matches("UC[A-Za-z0-9_-]{22}")
        && token != null
        && token.matches("[a-f0-9]{64}")
        && MessageDigest.isEqual(
            token(channel).getBytes(StandardCharsets.US_ASCII),
            token.getBytes(StandardCharsets.US_ASCII));
  }

  private String mac(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HMAC_UNAVAILABLE");
    }
  }

  @Override
  public String toString() {
    return "YouTubePushSettings[configured]";
  }
}
