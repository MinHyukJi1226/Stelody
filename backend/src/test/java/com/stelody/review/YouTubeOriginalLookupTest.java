package com.stelody.review;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.collector.config.CollectorSettings;
import com.stelody.review.domain.OriginalCredits;
import com.stelody.review.youtube.YouTubeOriginalLookup;
import com.stelody.review.youtube.YouTubeOriginalLookup.Target;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class YouTubeOriginalLookupTest {
  static final String SOURCE = "cover000001", SECOND = "cover000002", ORIGINAL = "original001";
  static final String MEMBER = "UC" + "m".repeat(22), ARTIST = "UC" + "a".repeat(22);
  final UUID first = new UUID(0, 1), second = new UUID(0, 2);
  WireMockServer server;

  @BeforeEach
  void start() {
    server = new WireMockServer(0);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  YouTubeOriginalLookup client(String key) {
    return new YouTubeOriginalLookup(
        new CollectorSettings(
            false,
            "",
            "",
            "",
            key,
            URI.create(server.baseUrl()),
            Duration.ofMillis(100),
            Duration.ofMillis(300),
            Duration.ofSeconds(2),
            1,
            Duration.ZERO));
  }

  Target target(UUID id, String video) {
    return new Target(id, video, MEMBER, List.of("Milky Way"), List.of(), null);
  }

  Map<String, Object> video(
      String id, String channel, String name, String title, String description) {
    return Map.of(
        "id",
        id,
        "snippet",
        Map.of(
            "channelId", channel, "channelTitle", name, "title", title, "description", description),
        "status",
        Map.of("privacyStatus", "public"));
  }

  void videos(String ids, List<Map<String, Object>> items) {
    server.stubFor(
        get(urlPathEqualTo("/videos"))
            .withQueryParam("id", equalTo(ids))
            .willReturn(
                okJson(
                    new ObjectMapper()
                        .writeValueAsString(
                            Map.of("kind", "youtube#videoListResponse", "items", items)))));
  }

  void search(String... ids) {
    server.stubFor(
        get(urlPathEqualTo("/search"))
            .willReturn(
                okJson(
                    new ObjectMapper()
                        .writeValueAsString(
                            Map.of(
                                "kind",
                                "youtube#searchListResponse",
                                "items",
                                Arrays.stream(ids)
                                    .map(id -> Map.of("id", Map.of("videoId", id)))
                                    .toList())))));
  }

  @Test
  void explicitOriginalCreditIsReadLiveAndNoSearchOrArbitraryUrlFetchIsNeeded() {
    videos(
        SOURCE,
        List.of(
            video(
                SOURCE,
                MEMBER,
                "Member",
                "Milky Way",
                "Original: Artist\nhttps://youtu.be/" + ORIGINAL + "\nMix: Engineer")));
    videos(
        ORIGINAL,
        List.of(video(ORIGINAL, ARTIST, "Artist", "Artist - Milky Way [Official MV]", "")));
    var found = client("key").find(List.of(target(first, SOURCE))).get(first).evidence();
    assertThat(found.artists()).containsExactly("Artist");
    assertThat(found.title()).isEqualTo("Milky Way");
    assertThat(found.method()).isEqualTo("DESCRIPTION");
    assertThat(found.sourceUrl()).isEqualTo(OriginalCredits.url(SOURCE));
    server.verify(0, getRequestedFor(urlPathEqualTo("/search")));
    server.verify(
        getRequestedFor(urlPathEqualTo("/videos"))
            .withHeader("X-Goog-Api-Key", equalTo("key"))
            .withoutQueryParam("key"));
  }

  @Test
  void absentCreditSearchesTitleAndRejectsCoverBeforeChoosingArtistMatchingOriginal() {
    videos(SOURCE, List.of(video(SOURCE, MEMBER, "Member", "Milky Way", "Mix: Engineer")));
    search("original001", "original002");
    videos(
        "original001,original002",
        List.of(
            video("original001", ARTIST, "Artist", "Artist - Milky Way Cover", ""),
            video("original002", ARTIST, "Artist", "Artist - Milky Way [Official MV]", "")));
    var found = client("key").find(List.of(target(first, SOURCE))).get(first).evidence();
    assertThat(found.videoId()).isEqualTo("original002");
    assertThat(found.method()).isEqualTo("SEARCH");
    server.verify(
        getRequestedFor(urlPathEqualTo("/search"))
            .withQueryParam("q", containing("Milky Way"))
            .withQueryParam("maxResults", equalTo("5"))
            .withQueryParam("type", equalTo("video")));
  }

  @Test
  void differentArtistsWithSameSongTitleAreNotResolvedBySearchRanking() {
    videos(SOURCE, List.of(video(SOURCE, MEMBER, "Member", "Milky Way", "")));
    search("original001", "original002");
    videos(
        "original001,original002",
        List.of(
            video("original001", ARTIST, "A", "A - Milky Way", ""),
            video("original002", ARTIST, "B", "B - Milky Way", "")));
    assertThat(client("key").find(List.of(target(first, SOURCE))).get(first).ambiguous()).isTrue();
  }

  @Test
  void quotaFailureKeepsExplicitArtistAndStopsFurtherRequestsInSameBatch() {
    videos(
        SOURCE + "," + SECOND,
        List.of(
            video(SOURCE, MEMBER, "Member", "Milky Way", "원곡자: Artist"),
            video(SECOND, MEMBER, "Member", "Milky Way", "")));
    server.stubFor(
        get(urlPathEqualTo("/search"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withBody("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}")));
    var results = client("key").find(List.of(target(first, SOURCE), target(second, SECOND)));
    assertThat(results.get(first).evidence().artists()).containsExactly("Artist");
    assertThat(results.get(first).evidence().videoId()).isNull();
    assertThat(results).doesNotContainKey(second);
    server.verify(1, getRequestedFor(urlPathEqualTo("/search")));
  }

  @Test
  void repeatedSongSearchIsCachedWithinOneBatchAndApiFailureReturnsNoInventedInformation() {
    videos(
        SOURCE + "," + SECOND,
        List.of(
            video(SOURCE, MEMBER, "Member", "Milky Way", ""),
            video(SECOND, MEMBER, "Member", "Milky Way", "")));
    search(ORIGINAL);
    videos(ORIGINAL, List.of(video(ORIGINAL, ARTIST, "Artist", "Artist - Milky Way", "")));
    assertThat(client("key").find(List.of(target(first, SOURCE), target(second, SECOND))))
        .hasSize(2);
    server.verify(1, getRequestedFor(urlPathEqualTo("/search")));
    server.verify(
        1, getRequestedFor(urlPathEqualTo("/videos")).withQueryParam("id", equalTo(ORIGINAL)));
    server.resetAll();
    server.stubFor(get(urlPathEqualTo("/videos")).willReturn(aResponse().withStatus(500)));
    assertThat(client("key").find(List.of(target(first, SOURCE)))).isEmpty();
    server.verify(1, getRequestedFor(urlPathEqualTo("/videos")));
  }

  @Test
  void conflictOrDifferentChannelCannotBecomeOriginalMetadata() {
    videos(
        SOURCE,
        List.of(
            video(
                SOURCE,
                MEMBER,
                "Member",
                "Milky Way",
                "Original: https://youtu.be/original001\n原曲: https://youtu.be/original002")));
    assertThat(client("key").find(List.of(target(first, SOURCE))).get(first).ambiguous()).isTrue();
    server.verify(0, getRequestedFor(urlPathEqualTo("/search")));
    videos(
        SOURCE,
        List.of(
            video(SOURCE, ARTIST, "Other", "Milky Way", "Original: https://youtu.be/" + ORIGINAL)));
    assertThat(client("key").find(List.of(target(first, SOURCE)))).isEmpty();
    server.verify(
        0, getRequestedFor(urlPathEqualTo("/videos")).withQueryParam("id", equalTo(ORIGINAL)));
  }

  @Test
  void existingOriginalNeedsCreditAgreementAndIsReusedWithoutSearchingWhenItMatches() {
    videos(SOURCE, List.of(video(SOURCE, MEMBER, "Member", "Milky Way", "원곡자: Artist")));
    var known =
        new Target(
            first, SOURCE, MEMBER, List.of("Milky Way"), List.of("Artist", "아티스트"), ORIGINAL);
    var evidence = client("key").find(List.of(known)).get(first).evidence();
    assertThat(evidence.videoId()).isEqualTo(ORIGINAL);
    server.verify(0, getRequestedFor(urlPathEqualTo("/search")));
    server.resetAll();
    videos(SOURCE, List.of(video(SOURCE, MEMBER, "Member", "Milky Way", "")));
    search("original002");
    videos("original002", List.of(video("original002", ARTIST, "Other", "Other - Milky Way", "")));
    var different = client("key").find(List.of(known)).get(first).evidence();
    assertThat(different.artists()).containsExactly("Other");
    server.verify(1, getRequestedFor(urlPathEqualTo("/search")));
  }

  @Test
  void missingApiKeyUsesNoExternalCalls() {
    assertThat(client("").find(List.of(target(first, SOURCE)))).isEmpty();
    server.verify(0, getRequestedFor(anyUrl()));
  }
}
