package com.stelody.collector;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.collector.push.*;
import com.stelody.collector.repository.DiscoveryRepository.Channel;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class YouTubePushTest {
  static final String CHANNEL = "UC" + "a".repeat(22), VIDEO = "abcdefghijk";
  static final String SECRET = "test-secret-" + "a".repeat(32);

  static YouTubePushSettings settings() {
    return new YouTubePushSettings(URI.create("https://backend.example.invalid"), SECRET);
  }

  public static byte[] feed(String channel, String video, Instant updated) {
    return ("<feed xmlns='http://www.w3.org/2005/Atom' xmlns:yt='http://www.youtube.com/xml/schemas/2015'><entry><yt:videoId>"
            + video
            + "</yt:videoId><yt:channelId>"
            + channel
            + "</yt:channelId><title>untrusted title</title><updated>"
            + updated
            + "</updated></entry></feed>")
        .getBytes(StandardCharsets.UTF_8);
  }

  public static String signature(
      YouTubePushSettings settings, String channel, byte[] body, String algorithm)
      throws Exception {
    var mac = javax.crypto.Mac.getInstance(algorithm.equals("sha1") ? "HmacSHA1" : "HmacSHA256");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            settings.deliverySecret(channel).getBytes(StandardCharsets.UTF_8), mac.getAlgorithm()));
    return algorithm + "=" + HexFormat.of().formatHex(mac.doFinal(body));
  }

  @Test
  void settingsRejectUnsafeOriginsAndNeverExposeSecrets() {
    for (String origin :
        List.of(
            "http://backend.example.invalid",
            "https://backend.example.invalid/path",
            "https://u@backend.example.invalid",
            "https://backend.example.invalid/?a=b"))
      assertThatThrownBy(() -> new YouTubePushSettings(URI.create(origin), SECRET))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new YouTubePushSettings(URI.create("https://backend.example.invalid"), "short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(settings().toString())
        .doesNotContain(SECRET, settings().token(CHANNEL), settings().deliverySecret(CHANNEL));
    assertThat(settings().accepts(CHANNEL, settings().token(CHANNEL))).isTrue();
    assertThat(settings().accepts("UC" + "b".repeat(22), settings().token(CHANNEL))).isFalse();
    assertThat(settings().deliverySecret(CHANNEL)).isNotEqualTo(settings().token(CHANNEL));
  }

  @Test
  void validFeedIsDeduplicatedButMalformedAndEntityExpandingXmlIsRejected() {
    var now = Instant.parse("2026-10-30T00:00:00Z");
    String original = new String(feed(CHANNEL, VIDEO, now), StandardCharsets.UTF_8);
    String entry =
        original.substring(original.indexOf("<entry>"), original.indexOf("</entry>") + 8);
    assertThat(
            YouTubePushFeed.parse(
                original.replace("</feed>", entry + "</feed>").getBytes(StandardCharsets.UTF_8),
                CHANNEL))
        .containsExactly(Map.entry(VIDEO, now));
    for (String invalid :
        List.of(
            original.replace(CHANNEL, "UC" + "b".repeat(22)),
            original.replace(VIDEO, "bad"),
            original.replace("<updated>", "<bad>"),
            "<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                + original.replace("untrusted title", "&x;"),
            original.replace("</feed>", entry.repeat(50) + "</feed>"),
            "<feed>malformed"))
      assertThatThrownBy(
              () -> YouTubePushFeed.parse(invalid.getBytes(StandardCharsets.UTF_8), CHANNEL))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> YouTubePushFeed.parse(new byte[YouTubePushFeed.MAX_BYTES + 1], CHANNEL))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void signaturesAreBoundToChannelAndExactBodyAndSupportGoogleHubSha1() throws Exception {
    var body = feed(CHANNEL, VIDEO, Instant.now());
    var endpoint = new YouTubePushEndpoint(settings(), null, null);
    for (String algorithm : List.of("sha1", "sha256")) {
      var sig = signature(settings(), CHANNEL, body, algorithm);
      assertThat(endpoint.signature(CHANNEL, body, sig)).isTrue();
      assertThat(endpoint.signature(CHANNEL, new byte[] {1}, sig)).isFalse();
      assertThat(endpoint.signature("UC" + "b".repeat(22), body, sig)).isFalse();
    }
    assertThat(endpoint.signature(CHANNEL, body, null)).isFalse();
    assertThat(endpoint.signature(CHANNEL, body, "sha1=" + "0".repeat(40))).isFalse();
    assertThat(endpoint.toString()).doesNotContain(SECRET);
  }

  @Test
  void subscriptionsRenewAfterVerificationExpireOrFailAndStopWhenChannelDisabled() {
    var server = new WireMockServer(0);
    server.start();
    var channels = new AtomicReference<>(List.of(new Channel(UUID.randomUUID(), CHANNEL)));
    var now = new AtomicReference<>(Instant.parse("2026-10-30T00:00:00Z"));
    var clock = org.mockito.Mockito.mock(Clock.class);
    org.mockito.Mockito.when(clock.instant()).thenAnswer(a -> now.get());
    server.stubFor(post(urlEqualTo("/subscribe")).willReturn(aResponse().withStatus(202)));
    try (var subscriptions =
        new YouTubePushSubscriptions(
            settings(), channels::get, clock, URI.create(server.baseUrl() + "/subscribe"))) {
      assertThatThrownBy(
              () ->
                  subscriptions.verify(
                      CHANNEL,
                      settings().token(CHANNEL),
                      "subscribe",
                      YouTubePushSettings.topic(CHANNEL),
                      "challenge",
                      86400L))
          .isInstanceOf(IllegalArgumentException.class);
      subscriptions.renew();
      server.verify(
          1,
          postRequestedFor(urlEqualTo("/subscribe"))
              .withRequestBody(containing("hub.secret=" + settings().deliverySecret(CHANNEL)))
              .withRequestBody(containing("hub.lease_seconds=86400")));
      assertThat(
              subscriptions.verify(
                  CHANNEL,
                  settings().token(CHANNEL),
                  "subscribe",
                  YouTubePushSettings.topic(CHANNEL),
                  "challenge",
                  86400L))
          .isEqualTo("challenge");
      subscriptions.renew();
      server.verify(1, postRequestedFor(urlEqualTo("/subscribe")));
      now.set(now.get().plusSeconds(85000));
      subscriptions.renew();
      server.verify(2, postRequestedFor(urlEqualTo("/subscribe")));
      for (String mode : List.of("unsubscribe", "denied", ""))
        assertThatThrownBy(
                () ->
                    subscriptions.verify(
                        CHANNEL,
                        settings().token(CHANNEL),
                        mode,
                        YouTubePushSettings.topic(CHANNEL),
                        "challenge",
                        86400L))
            .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  subscriptions.verify(
                      CHANNEL,
                      settings().token(CHANNEL),
                      "subscribe",
                      "https://attacker.invalid/",
                      "challenge",
                      86400L))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  subscriptions.verify(
                      CHANNEL,
                      settings().token(CHANNEL),
                      "subscribe",
                      YouTubePushSettings.topic(CHANNEL),
                      "challenge",
                      0L))
          .isInstanceOf(IllegalArgumentException.class);
      now.set(now.get().plusSeconds(301));
      server.stubFor(post(urlEqualTo("/subscribe")).willReturn(serverError()));
      subscriptions.renew();
      server.verify(3, postRequestedFor(urlEqualTo("/subscribe")));
      channels.set(List.of());
      subscriptions.renew();
      server.verify(3, postRequestedFor(urlEqualTo("/subscribe")));
      assertThat(subscriptions.allowed(CHANNEL, settings().token(CHANNEL))).isFalse();
    } finally {
      server.stop();
    }
  }

  @Test
  void configurationIsOptInAndRequiresCollectorAndDiscovery() {
    var runner =
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(YouTubePushConfiguration.class);
    runner.run(c -> assertThat(c).doesNotHaveBean(YouTubePushEndpoint.class));
    runner
        .withPropertyValues(
            "stelody.collector.push.enabled=true",
            "stelody.collector.enabled=true",
            "DISCOVERY_ENABLED=false")
        .run(c -> assertThat(c).doesNotHaveBean(YouTubePushEndpoint.class));
    runner
        .withPropertyValues(
            "stelody.collector.push.enabled=true",
            "stelody.collector.enabled=true",
            "DISCOVERY_ENABLED=true",
            "stelody.collector.push.origin=https://backend.example.invalid",
            "stelody.collector.push.secret=" + SECRET,
            "stelody.collector.db-url=jdbc:postgresql://127.0.0.1:1/test",
            "stelody.collector.db-username=collector",
            "stelody.collector.db-password=test",
            "stelody.collector.api-key=test")
        .run(c -> assertThat(c).hasSingleBean(YouTubePushEndpoint.class));
    runner
        .withPropertyValues(
            "stelody.collector.push.enabled=true",
            "stelody.collector.enabled=true",
            "DISCOVERY_ENABLED=true",
            "stelody.collector.push.origin=http://unsafe.invalid",
            "stelody.collector.push.secret=" + SECRET)
        .run(c -> assertThat(c).hasFailed());
  }
}
