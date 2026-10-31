package com.stelody.review;

import static org.assertj.core.api.Assertions.*;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.collector.domain.*;
import com.stelody.collector.repository.CollectionRuleStore.Snapshot;
import com.stelody.review.domain.ReviewRegistrationRules;
import com.stelody.review.dto.ReviewDtos;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewRegistrationRulesTest {
  final ReviewRegistrationRules rules = new ReviewRegistrationRules();
  final UUID id = new UUID(0, 1), other = new UUID(0, 2);
  final CoverPublicationRules.Member member =
      new CoverPublicationRules.Member(id, 0, List.of("아오쿠모 린", "Aokumo Rin"));
  final CoverPublicationRules.Member another =
      new CoverPublicationRules.Member(other, 0, List.of("유즈하 리코"));
  final Snapshot configuration = new Snapshot(0, RuleConfiguration.defaults());
  final Channel channel = new Channel(id, 0, "UC" + "a".repeat(22), "멤버 채널", id, "MEMBER", true);

  ReviewDtos.Item source(String title, String type) {
    return new ReviewDtos.Item(
        id,
        "abcdefghijk",
        id,
        "멤버 채널",
        title,
        Instant.now(),
        null,
        240L,
        Instant.now(),
        "PUBLIC",
        "REVIEW",
        type,
        "title-v1",
        "reason",
        "PENDING",
        null,
        Instant.now(),
        0,
        false,
        null,
        null);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Milky Way / 아오쿠모 린 Cover",
        "Milky Way Covered by Aokumo Rin",
        "Milky Way / 아오쿠모 린 (Aokumo Rin) Cover"
      })
  void usesExistingSoloCreditEvidenceAndPreservesDisplayCase(String title) {
    var plan = rules.plan(source(title, "COVER"), channel, List.of(member, another), configuration);
    assertThat(plan.song().title()).isEqualTo("Milky Way");
    assertThat(plan.song().memberIds()).containsExactly(id);
    assertThat(plan.song().visibility()).isEqualTo(Visibility.DRAFT);
    assertThat(plan.kind()).isEqualTo(VideoKind.OFFICIAL_COVER);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "노래 Cover",
        "ABYSS Cover",
        "노래 / 유즈하 리코 & 아오쿠모 린 Cover",
        "노래 feat. Guest / 아오쿠모 린 Cover",
        "노래 / guest x 아오쿠모 린 Cover",
        "노래 / Guest 아오쿠모 린 Cover"
      })
  void ambiguousParticipantsAreNotInferredFromChannelOwnership(String title) {
    var plan = rules.plan(source(title, "COVER"), channel, List.of(member, another), configuration);
    assertThat(plan.song().memberIds()).isEmpty();
    assertThat(plan.song().externalArtistIds()).isEmpty();
    assertThat(plan.song().title()).isEqualTo(title);
    assertThat(plan.song().visibility()).isEqualTo(Visibility.DRAFT);
  }

  @Test
  void conflictingOwnerAndDuplicateAliasesCannotConfirmSoloParticipant() {
    assertThat(
            rules
                .plan(
                    source("곡 / 유즈하 리코 Cover", "COVER"),
                    channel,
                    List.of(member, another),
                    configuration)
                .song()
                .memberIds())
        .isEmpty();
    var duplicate = new CoverPublicationRules.Member(other, 0, List.of("Aokumo Rin"));
    assertThat(
            rules
                .plan(
                    source("곡 / Aokumo Rin Cover", "COVER"),
                    channel,
                    List.of(member, duplicate),
                    configuration)
                .song()
                .memberIds())
        .isEmpty();
  }

  @Test
  void groupChannelCanUseExplicitSoloCreditWithoutAssumingAGroupPerformer() {
    var group = new Channel(other, 0, "UC" + "b".repeat(22), "그룹", null, "GROUP", true);
    assertThat(
            rules
                .plan(source("곡 / 아오쿠모 린 Cover", "COVER"), group, List.of(member), configuration)
                .song()
                .memberIds())
        .containsExactly(id);
    assertThat(
            rules
                .plan(source("ABYSS Cover", "COVER"), group, List.of(member), configuration)
                .song()
                .memberIds())
        .isEmpty();
  }

  @Test
  void classifiesUnknownWithConfiguredRulesAndStillRejectsTypeConflicts() {
    assertThat(
            rules
                .plan(source("곡 Cover", "UNKNOWN"), channel, List.of(), configuration)
                .song()
                .type())
        .isEqualTo(SongType.COVER);
    assertThatThrownBy(
            () -> rules.plan(source("곡 original", "COVER"), channel, List.of(), configuration))
        .isInstanceOf(ReviewRegistrationRules.Unresolved.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "IA - オツキミリサイタル 달맞이 리사이틀 / 네네코 마시로 (4K)",
        "곡 / 아오쿠모 린 & 유즈하 리코",
        "ABYSS 노래",
        "곡 feat. Guest"
      })
  void unknownTitleDefaultsToCoverDraftWithMissingInformationLeftEmpty(String title) {
    var source = source(title, "UNKNOWN");
    var plan = rules.plan(source, channel, List.of(member, another), configuration);
    assertThat(plan.song().type()).isEqualTo(SongType.COVER);
    assertThat(plan.kind()).isEqualTo(VideoKind.OFFICIAL_COVER);
    assertThat(plan.song().title()).isEqualTo(title);
    assertThat(plan.song().visibility()).isEqualTo(Visibility.DRAFT);
    assertThat(plan.song().workId()).isNull();
    assertThat(plan.song().memberIds()).isEmpty();
    assertThat(plan.song().externalArtistIds()).isEmpty();
    assertThat(plan.song().aliases()).isEmpty();
    assertThat(plan.song().links()).isEmpty();
    assertThat(plan.song().karaoke()).isEmpty();
    assertThat(plan.song().searchVisibility()).isEqualTo(SearchVisibility.UNCHECKED);
    assertThat(plan.song().isSpecialEvent()).isFalse();
    assertThat(plan.song().specialEventLabel()).isNull();
    assertThat(source.suggestedType()).isEqualTo("UNKNOWN");
    var observation =
        new VideoObservation(
            source.youtubeId(),
            channel.youtubeId(),
            source.availability(),
            title,
            source.publishedAt(),
            source.thumbnailUrl(),
            source.durationSeconds(),
            false,
            null);
    assertThat(configuration.rules().decide(observation, true).suggestedType())
        .isEqualTo("UNKNOWN");
  }

  @Test
  void explicitAndStoredOriginalTypesArePreservedBeforeApplyingCoverDefault() {
    for (var source : List.of(source("곡 Original", "UNKNOWN"), source("새 노래", "ORIGINAL"))) {
      assertThat(rules.plan(source, channel, List.of(), configuration).song().type())
          .isEqualTo(SongType.ORIGINAL);
    }
  }

  @Test
  void originalVideoKindUsesEvidenceOrExistingOtherValueWithoutGuessingParticipants() {
    for (var entry :
        Map.of(
                "곡 Official MV",
                VideoKind.OFFICIAL_MV,
                "곡 Original Audio",
                VideoKind.AUDIO,
                "곡 오리지널",
                VideoKind.OTHER,
                "곡 MV Audio",
                VideoKind.OTHER)
            .entrySet()) {
      var plan =
          rules.plan(source(entry.getKey(), "ORIGINAL"), channel, List.of(member), configuration);
      assertThat(plan.kind()).isEqualTo(entry.getValue());
      assertThat(plan.song().memberIds()).isEmpty();
      assertThat(plan.song().visibility()).isEqualTo(Visibility.DRAFT);
    }
  }

  @Test
  void emptyOrOversizedTitleAndExcludedContentAreNotSilentlyTruncatedOrRegistered() {
    for (String title : List.of(" ", "가".repeat(301), "곡 Cover #Shorts"))
      assertThatThrownBy(
              () -> rules.plan(source(title, "COVER"), channel, List.of(member), configuration))
          .isInstanceOf(ReviewRegistrationRules.Unresolved.class);
  }
}
