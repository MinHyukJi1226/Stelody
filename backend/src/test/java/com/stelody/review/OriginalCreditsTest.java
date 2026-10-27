package com.stelody.review;

import static org.assertj.core.api.Assertions.*;

import com.stelody.review.domain.OriginalCredits;
import com.stelody.review.domain.OriginalCredits.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OriginalCreditsTest {
  static final String SOURCE = "cover000001", ORIGINAL = "original001";

  Video video(String channel, String title, String description) {
    return new Video(ORIGINAL, "UC" + "a".repeat(22), channel, title, description);
  }

  @Test
  void readsOnlyExplicitOriginalCreditBlocksAndCanonicalizesUrls() {
    var hint =
        OriginalCredits.hint(
            "Vocal: 멤버\nMix: 믹서\n원곡자: DECO*27\n원곡:\nhttps://youtu.be/"
                + ORIGINAL
                + "?si=test\nIllustration: 작가\nhttps://youtu.be/other000001");
    assertThat(hint.artists()).containsExactly("DECO*27");
    assertThat(hint.videoId()).isEqualTo(ORIGINAL);
    assertThat(hint.conflicting()).isFalse();
    assertThat(OriginalCredits.hint("Mix: 믹서\nhttps://youtu.be/" + ORIGINAL).videoId()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Original", "Original song", "원곡", "本家", "原曲"})
  void recognizesExplicitLabelsWithoutDependingOnCoverTitleKeywords(String label) {
    var hint =
        OriginalCredits.hint(
            label + ": 작곡가\nhttps://www.youtube.com/watch?v=" + ORIGINAL + "&list=test");
    assertThat(hint.artists()).containsExactly("작곡가");
    assertThat(hint.videoId()).isEqualTo(ORIGINAL);
  }

  @Test
  void multipleOriginalsAndContradictoryArtistCreditsRemainAmbiguous() {
    assertThat(
            OriginalCredits.hint(
                    "Original: https://youtu.be/original001\n原曲: https://youtu.be/original002")
                .conflicting())
        .isTrue();
    assertThat(OriginalCredits.hint("Original artist: A\n원곡자: B").conflicting()).isTrue();
    assertThat(OriginalCredits.hint("원곡자: A & B").artists()).containsExactly("A", "B");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://youtube.com.evil.invalid/watch?v=original001",
        "https://youtube.com@evil.invalid/watch?v=original001",
        "https://localhost/watch?v=original001",
        "file:///original001",
        "https://youtu.be/original001/extra",
        "https://youtube.com/watch?v=original001&v=original002"
      })
  void doesNotFollowArbitraryDescriptionLinks(String value) {
    assertThat(OriginalCredits.youtubeId(value)).isEmpty();
  }

  @Test
  void descriptionCanIdentifyAnOriginalWithoutInventingAnArtist() {
    var hint = OriginalCredits.hint("Original: https://youtu.be/" + ORIGINAL);
    var result = OriginalCredits.direct(video("不明", "Milky Way", ""), hint, SOURCE).orElseThrow();
    assertThat(result.title()).isEqualTo("Milky Way");
    assertThat(result.artists()).isEmpty();
    assertThat(result.sourceUrl()).isEqualTo(OriginalCredits.url(SOURCE));
    assertThat(OriginalCredits.direct(video("A", "Milky Way Cover", ""), hint, SOURCE)).isEmpty();
  }

  @Test
  void genericOriginalSongTitleCannotBecomeAnArtistName() {
    var hint = OriginalCredits.hint("Original: Milky Way\nhttps://youtu.be/" + ORIGINAL);
    var evidence =
        OriginalCredits.direct(video("Artist", "Artist - Milky Way", ""), hint, SOURCE)
            .orElseThrow();
    assertThat(evidence.artists()).containsExactly("Artist");
    var explicit =
        OriginalCredits.hint("Original: Milky Way\n원곡자: Artist\nhttps://youtu.be/" + ORIGINAL);
    assertThat(explicit.conflicting()).isFalse();
    assertThat(explicit.artists()).containsExactly("Artist");
  }

  @Test
  void searchRequiresMatchingSongAndArtistEvidenceInsteadOfUploaderNameAlone() {
    var titles = List.of("Milky Way");
    assertThat(
            OriginalCredits.searched(
                video("Artist", "Artist - Milky Way [Official MV]", ""), titles, List.of()))
        .isPresent();
    assertThat(
            OriginalCredits.searched(
                video("Artist OFFICIAL", "Milky Way", ""), titles, List.of("Artist")))
        .isPresent();
    assertThat(OriginalCredits.searched(video("Artist", "Milky Way", ""), titles, List.of()))
        .isEmpty();
    assertThat(
            OriginalCredits.searched(
                video("Artist", "Artist - Milky Way Cover", ""), titles, List.of()))
        .isEmpty();
    assertThat(
            OriginalCredits.searched(
                video("Artist", "Artist - Different song", ""), titles, List.of()))
        .isEmpty();
    assertThat(
            OriginalCredits.searched(
                video("Artist", "Artist - Milky Way", ""), titles, List.of("Other artist")))
        .isEmpty();
  }

  @Test
  void compoundOriginalCreditUsesSongMatchToSeparateArtistFromTitle() {
    var hint = OriginalCredits.hint("Original: 米津玄師 - LADY\nhttps://youtu.be/" + ORIGINAL);
    var found =
        OriginalCredits.direct(
                video("Kenshi Yonezu 米津玄師", "米津玄師 - LADY", ""), hint, SOURCE, List.of("LADY"))
            .orElseThrow();
    assertThat(found.title()).isEqualTo("LADY");
    assertThat(found.artists()).containsExactly("米津玄師");
    assertThat(
            OriginalCredits.searched(video("Ado", "【Ado】うっせぇわ", ""), List.of("うっせぇわ"), List.of()))
        .isPresent();
  }

  @Test
  void topicArtistNeedsDistributorMetadataAndDoesNotUseProductionCreditsAsArtist() {
    var result =
        OriginalCredits.searched(
                video(
                    "Singer - Topic",
                    "Milky Way",
                    "Provided to YouTube by Label\nMix: Engineer\nAuto-generated by YouTube."),
                List.of("Milky Way"),
                List.of())
            .orElseThrow();
    assertThat(result.artists()).containsExactly("Singer");
    assertThat(
            OriginalCredits.searched(
                video("Singer - Topic", "Milky Way", "Mix: Engineer"),
                List.of("Milky Way"),
                List.of()))
        .isEmpty();
  }

  @Test
  void removesPerformerSuffixAndRecognizesJapaneseTitleInBilingualCoverTitle() {
    assertThat(OriginalCredits.titles("IA - オツキミリサイタル 달맞이 리사이틀 / 네네코 마시로 (4K)"))
        .contains("オツキミリサイタル", "オツキミリサイタル 달맞이 리사이틀")
        .noneMatch(t -> t.contains("마시로") || t.contains("4K"));
    assertThat(OriginalCredits.titles("Milky Way / 멤버 Cover")).containsExactly("Milky Way");
    assertThat(OriginalCredits.titles("[A]ddiction / 멤버 Cover")).containsExactly("[A]ddiction");
  }

  @Test
  void uniqueWorkCanHaveTwoRecordingsButDifferentArtistMatchesAreAmbiguous() {
    var a = new Evidence("Milky Way", List.of("A"), "original001", "source", "SEARCH");
    var same = new Evidence("Milky Way", List.of("A"), "original002", "source", "SEARCH");
    var other = new Evidence("Milky Way", List.of("B"), "original003", "source", "SEARCH");
    assertThat(OriginalCredits.unique(List.of(same, a))).contains(a);
    assertThat(OriginalCredits.unique(List.of(a, other))).isEmpty();
  }
}
