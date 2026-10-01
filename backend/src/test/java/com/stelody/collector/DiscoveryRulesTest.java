package com.stelody.collector;

import static org.assertj.core.api.Assertions.*;

import com.stelody.collector.domain.DiscoveryRules;
import com.stelody.collector.domain.VideoObservation;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DiscoveryRulesTest {
  VideoObservation video(String title, long duration) {
    return new VideoObservation(
        "YT000000001",
        "UC" + "a".repeat(22),
        "PUBLIC",
        title,
        Instant.now(),
        null,
        duration,
        true,
        100L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Test COVER / singer", "test Covered by Singer", "歌ってみた"})
  void coverMarkersAreSuggestionsRequiringParticipantReview(String title) {
    var result = new DiscoveryRules().decide(video(title, 120), true);
    assertThat(result.disposition()).isEqualTo("REVIEW");
    assertThat(result.suggestedType()).isEqualTo("COVER");
    assertThat(result.reason()).contains("PARTICIPANT_REVIEW");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Discovery", "Recover", "coveredby", "shortstay"})
  void wordFragmentsNeverTriggerRules(String title) {
    assertThat(new DiscoveryRules().decide(video(title, 120), true).suggestedType())
        .isEqualTo("UNKNOWN");
  }

  @Test
  void durationDoesNotEstablishShortsAndExclusionOverridesCover() {
    assertThat(new DiscoveryRules().decide(video("test Cover", 30), true).disposition())
        .isEqualTo("REVIEW");
    assertThat(new DiscoveryRules().decide(video("test Cover #Shorts", 240), true).disposition())
        .isEqualTo("EXCLUDED");
  }

  @Test
  void classificationRequiresSeparatePolicyOptIn() {
    assertThat(new DiscoveryRules().decide(video("COVER #Shorts", 120), false).reason())
        .isEqualTo("CLASSIFICATION_DISABLED");
    assertThat(
            new DiscoveryRules()
                .decide(VideoObservation.unavailable("YT000000001"), true)
                .disposition())
        .isEqualTo("DEFERRED");
  }

  @Test
  void originalMarkersNeverPublish() {
    var result = new DiscoveryRules().decide(video("Original MV", 240), true);
    assertThat(result.suggestedType()).isEqualTo("ORIGINAL");
    assertThat(result.disposition()).isEqualTo("REVIEW");
  }
}
