package com.stelody.collector;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import com.stelody.collector.youtube.YouTubeUploadsClient;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class YouTubeUploadsClientTest {
  final String channel = "UC" + "a".repeat(22), playlist = "UU" + "a".repeat(22);
  WireMockServer server;
  YouTubeUploadsClient client;

  @BeforeEach
  void start() {
    server = new WireMockServer(0);
    server.start();
    client =
        new YouTubeUploadsClient(
            new CollectorSettings(
                true,
                "jdbc:postgresql:test",
                "test",
                "test",
                "test-key",
                URI.create(server.baseUrl()),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                3,
                Duration.ZERO),
            d -> {});
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  CollectionBudget budget() {
    return new CollectionBudget(Duration.ofSeconds(10));
  }

  @Test
  void resolvesUploadsUsingChannelIdAndKeyHeader() {
    server.stubFor(
        get(urlPathEqualTo("/channels"))
            .willReturn(
                okJson(
                    """
      {"kind":"youtube#channelListResponse","items":[{"id":"%s","contentDetails":{"relatedPlaylists":{"uploads":"%s"}}}]}
      """
                        .formatted(channel, playlist))));
    assertThat(client.uploads(channel, budget())).isEqualTo(playlist);
    server.verify(
        getRequestedFor(urlPathEqualTo("/channels"))
            .withHeader("X-Goog-Api-Key", equalTo("test-key"))
            .withQueryParam("id", equalTo(channel))
            .withQueryParam("part", equalTo("contentDetails"))
            .withQueryParam("key", absent()));
  }

  String item(String id) {
    return """
      {"snippet":{"playlistId":"%s","channelId":"%s","resourceId":{"kind":"youtube#video","videoId":"%s"}},"contentDetails":{"videoId":"%s"}}
      """
        .formatted(playlist, channel, id, id);
  }

  @Test
  void passesOpaqueTokenAndReadsVideoIdRatherThanPlaylistItemId() {
    server.stubFor(
        get(urlPathEqualTo("/playlistItems"))
            .willReturn(
                okJson(
                    """
      {"kind":"youtube#playlistItemListResponse","items":[%s],"nextPageToken":"next"}
      """
                        .formatted(item("YT000000001")))));
    var page = client.page(channel, playlist, "opaque+token/=", budget());
    assertThat(page.videoIds()).containsExactly("YT000000001");
    assertThat(page.nextToken()).isEqualTo("next");
    server.verify(
        getRequestedFor(urlPathEqualTo("/playlistItems"))
            .withQueryParam("pageToken", equalTo("opaque+token/="))
            .withQueryParam("maxResults", equalTo("50")));
  }

  @Test
  void missingChannelIsNotAnEmptySuccessfulScan() {
    server.stubFor(
        get(urlPathEqualTo("/channels"))
            .willReturn(okJson("{\"kind\":\"youtube#channelListResponse\",\"items\":[]}")));
    assertThatThrownBy(() -> client.uploads(channel, budget()))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("CHANNEL_UNAVAILABLE");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong-list",
        "wrong-channel",
        "duplicate",
        "same-token",
        "empty-more",
        "wrong-video"
      })
  void malformedPageNeverAdvancesCheckpoint(String variant) {
    String first = item("YT000000001");
    if (variant.equals("wrong-list")) first = first.replace(playlist, "UU" + "b".repeat(22));
    if (variant.equals("wrong-channel")) first = first.replace(channel, "UC" + "b".repeat(22));
    if (variant.equals("wrong-video"))
      first =
          first.replace(
              "\"contentDetails\":{\"videoId\":\"YT000000001\"}",
              "\"contentDetails\":{\"videoId\":\"YT000000002\"}");
    String items =
        variant.equals("duplicate")
            ? first + "," + first
            : variant.equals("empty-more") ? "" : first;
    String extra =
        variant.equals("same-token") || variant.equals("empty-more")
            ? ",\"nextPageToken\":\"same\""
            : "";
    server.stubFor(
        get(urlPathEqualTo("/playlistItems"))
            .willReturn(
                okJson(
                    "{\"kind\":\"youtube#playlistItemListResponse\",\"items\":["
                        + items
                        + "]"
                        + extra
                        + "}")));
    assertThatThrownBy(() -> client.page(channel, playlist, "same", budget()))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("INVALID_RESPONSE");
  }

  @Test
  void invalidPageTokenHasNormalizedReason() {
    server.stubFor(
        get(urlPathEqualTo("/playlistItems"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"invalidPageToken\"}]}}")));
    assertThatThrownBy(() -> client.page(channel, playlist, "expired", budget()))
        .hasMessage("INVALID_PAGE_TOKEN");
    server.verify(1, getRequestedFor(urlPathEqualTo("/playlistItems")));
  }
}
