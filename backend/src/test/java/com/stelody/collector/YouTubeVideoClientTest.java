package com.stelody.collector;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.youtube.YouTubeVideoClient;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class YouTubeVideoClientTest {
  WireMockServer server;
  static final String ID = "YT000000001", CHANNEL = "UC" + "a".repeat(22);

  @BeforeEach
  void start() {
    server = new WireMockServer(0);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  CollectorSettings settings(int attempts, Duration timeout) {
    return new CollectorSettings(
        true,
        "jdbc:postgresql://test/test",
        "collector",
        "secret",
        "test-api-key",
        URI.create(server.baseUrl()),
        Duration.ofMillis(100),
        timeout,
        Duration.ofSeconds(5),
        attempts,
        Duration.ofMillis(1));
  }

  String video(String extras, String count) {
    return """
      {"kind":"youtube#videoListResponse","items":[{"id":"%s",
       "snippet":{"channelId":"%s","title":"테스트 영상","publishedAt":"2026-10-01T00:00:00Z",
         "thumbnails":{"high":{"url":"https://i.ytimg.com/test.jpg"}}},
       "contentDetails":{"duration":"PT3M12S"},
       "status":{"privacyStatus":"public","embeddable":true},
       "statistics":{%s}%s}]}
      """
        .formatted(ID, CHANNEL, count, extras);
  }

  void response(int status, String body) {
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  @Test
  void requestsRegisteredIdsWithHeaderKeyAndParsesOnlyRequiredData() {
    response(200, video("", "\"viewCount\":\"12345\""));
    var observations =
        new YouTubeVideoClient(settings(3, Duration.ofSeconds(1)), d -> {})
            .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5)));
    var value = observations.get(ID);
    assertThat(value.viewCount()).isEqualTo(12345);
    assertThat(value.durationSeconds()).isEqualTo(192);
    assertThat(value.availability()).isEqualTo("PUBLIC");
    assertThat(value.liveStreamingDetailsPresent()).isFalse();
    server.verify(
        getRequestedFor(urlPathEqualTo("/videos"))
            .withQueryParam("id", equalTo(ID))
            .withQueryParam(
                "part", equalTo("snippet,contentDetails,status,statistics,liveStreamingDetails"))
            .withHeader("X-Goog-Api-Key", equalTo("test-api-key"))
            .withoutQueryParam("key"));
    assertThat(settings(3, Duration.ofSeconds(1)).toString())
        .doesNotContain("secret", "test-api-key");
  }

  @Test
  void completedBroadcastRetainsHistoryDespiteNoneStatus() {
    response(
        200,
        video(
                ",\"liveStreamingDetails\":{\"actualStartTime\":\"2026-10-01T00:00:00Z\",\"actualEndTime\":\"2026-10-01T00:03:12Z\"}",
                "")
            .replace(
                "\"title\":\"테스트 영상\"", "\"title\":\"테스트 영상\",\"liveBroadcastContent\":\"none\""));
    var value =
        new YouTubeVideoClient(settings(1, Duration.ofSeconds(1)), d -> {})
            .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5)))
            .get(ID);
    assertThat(value.liveBroadcastContent()).isEqualTo("none");
    assertThat(value.liveStreamingDetailsPresent()).isTrue();
    assertThat(value.availability()).isEqualTo("PUBLIC");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"scheduledStartTime\":\"2026-10-02T00:00:00Z\"}",
        "{\"actualStartTime\":\"2026-10-01T00:00:00Z\"}"
      })
  void broadcastObjectPresenceDoesNotRequireEndTime(String details) {
    response(200, video(",\"liveStreamingDetails\":" + details, ""));
    var value =
        new YouTubeVideoClient(settings(1, Duration.ofSeconds(1)), d -> {})
            .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5)))
            .get(ID);
    assertThat(value.liveStreamingDetailsPresent()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "\"none\"", "true", "0"})
  void malformedBroadcastMetadataCannotBeTreatedAsNoBroadcast(String details) {
    response(200, video(",\"liveStreamingDetails\":" + details, ""));
    assertThatThrownBy(
            () ->
                new YouTubeVideoClient(settings(1, Duration.ofSeconds(1)), d -> {})
                    .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(
            CollectionFailure.class, e -> assertThat(e.code()).isEqualTo("INVALID_RESPONSE"));
  }

  @Test
  void successfulOmissionIsUnavailableAndMissingCountIsNeverZero() {
    response(200, "{\"kind\":\"youtube#videoListResponse\",\"items\":[]}");
    var client = new YouTubeVideoClient(settings(1, Duration.ofSeconds(1)), d -> {});
    var value = client.fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5))).get(ID);
    assertThat(value.availability()).isEqualTo("UNAVAILABLE");
    assertThat(value.viewCount()).isNull();
    response(200, video("", ""));
    assertThat(
            client
                .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5)))
                .get(ID)
                .viewCount())
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 503})
  void transientHttpFailuresAreRetriedAtMostThreeTimes(int status) {
    response(status, "{\"error\":{\"message\":\"private body\"}}");
    var delays = new java.util.ArrayList<Duration>();
    assertThatThrownBy(
            () ->
                new YouTubeVideoClient(settings(3, Duration.ofSeconds(1)), delays::add)
                    .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(
            CollectionFailure.class, e -> assertThat(e.code()).isEqualTo("API_HTTP_" + status))
        .hasMessageNotContaining("private body")
        .hasMessageNotContaining("test-api-key");
    assertThat(delays).containsExactly(Duration.ofMillis(1), Duration.ofMillis(2));
    server.verify(3, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void retryCanRecoverAndQuotaStopsImmediately() {
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .inScenario("retry")
            .whenScenarioStateIs("Started")
            .willSetStateTo("ready")
            .willReturn(aResponse().withStatus(503)));
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .inScenario("retry")
            .whenScenarioStateIs("ready")
            .willReturn(okJson(video("", "\"viewCount\":\"0\""))));
    var client = new YouTubeVideoClient(settings(3, Duration.ofSeconds(1)), d -> {});
    assertThat(
            client
                .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5)))
                .get(ID)
                .viewCount())
        .isZero();
    server.resetAll();
    response(403, "{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}");
    assertThatThrownBy(() -> client.fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(
            CollectionFailure.class, e -> assertThat(e.code()).isEqualTo("QUOTA_EXHAUSTED"));
    server.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404})
  void permanentHttpErrorsDoNotBecomeMissingVideosOrRetry(int code) {
    response(code, "{}");
    assertThatThrownBy(
            () ->
                new YouTubeVideoClient(settings(3, Duration.ofSeconds(1)), d -> {})
                    .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5))))
        .isInstanceOf(CollectionFailure.class);
    server.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"kind\":\"youtube#videoListResponse\"}",
        "{",
        "{\"kind\":\"youtube#videoListResponse\",\"items\":[{\"id\":\"other\"}]}",
        "{\"kind\":\"youtube#videoListResponse\",\"items\":[],\"nextPageToken\":\"unexpected\"}"
      })
  void malformedResponsesAreRejectedWithoutGuessingAvailability(String body) {
    response(200, body);
    assertThatThrownBy(
            () ->
                new YouTubeVideoClient(settings(1, Duration.ofSeconds(1)), d -> {})
                    .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(
            CollectionFailure.class, e -> assertThat(e.code()).isEqualTo("INVALID_RESPONSE"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "1.2", "9223372036854775808", "NaN"})
  void unsupportedCountsFailTheWholeBatch(String count) {
    response(200, video("", "\"viewCount\":\"" + count + "\""));
    assertThatThrownBy(
            () ->
                new YouTubeVideoClient(settings(1, Duration.ofSeconds(1)), d -> {})
                    .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(
            CollectionFailure.class, e -> assertThat(e.code()).isEqualTo("INVALID_RESPONSE"));
  }

  @Test
  void deadlinePreventsWaitingBeyondTheRunBudget() {
    var nanos = new AtomicLong();
    var budget = new CollectionBudget(Duration.ofMillis(10), nanos::get);
    budget.pause(Duration.ofMillis(9), d -> nanos.addAndGet(d.toNanos()));
    assertThatThrownBy(() -> budget.pause(Duration.ofMillis(2), d -> fail("must not sleep")))
        .isInstanceOfSatisfying(
            CollectionFailure.class, e -> assertThat(e.code()).isEqualTo("TIME_LIMIT"));
    nanos.set(Duration.ofMillis(11).toNanos());
    assertThatThrownBy(budget::remaining).isInstanceOf(CollectionFailure.class);
  }

  @Test
  void requestTimeoutIsBounded() {
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .willReturn(okJson(video("", "\"viewCount\":\"1\"")).withFixedDelay(500)));
    assertThatThrownBy(
            () ->
                new YouTubeVideoClient(settings(1, Duration.ofMillis(50)), d -> {})
                    .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(2))))
        .isInstanceOfSatisfying(
            CollectionFailure.class, e -> assertThat(e.code()).isEqualTo("API_IO_ERROR"));
  }

  @Test
  void slowResponseBodyAlsoHonorsRequestTimeout() {
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .willReturn(okJson(video("", "\"viewCount\":\"1\"")).withChunkedDribbleDelay(5, 1000)));
    long started = System.nanoTime();
    assertThatThrownBy(
            () ->
                new YouTubeVideoClient(settings(1, Duration.ofMillis(100)), d -> {})
                    .fetch(List.of(ID), new CollectionBudget(Duration.ofSeconds(3))))
        .isInstanceOf(CollectionFailure.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(800));
  }
}
