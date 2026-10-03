package com.stelody.collector;

import static org.assertj.core.api.Assertions.*;

import com.stelody.collector.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleConfigurationTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"DISCOVERY", "recovered by singer", "coveredby", "Ｍｙ Ｄｉｓｃｏｖｅｒｙ", "歌cover曲"})
  void unicodeWordsDoNotMatchFragments(String title) {
    var v = new VideoObservation("", null, "PUBLIC", title, null, null, null, false, null);
    assertThat(
            new DiscoveryRules(RuleConfiguration.defaults(), "title-v2:3")
                .decide(v, true)
                .suggestedType())
        .isEqualTo("UNKNOWN");
  }

  @Test
  void normalizedKeywordsAndLiteralPunctuationAreSupported() {
    var rule =
        new RuleConfiguration(
                List.of(new RuleConfiguration.Marker(" ＣＯＶＥＲ　 ", RuleConfiguration.Match.WORD)),
                List.of(),
                List.of(new RuleConfiguration.Marker("(a+)+", RuleConfiguration.Match.PHRASE)))
            .validated();
    var rules = new DiscoveryRules(rule, "title-v2:4");
    assertThat(
            rules
                .decide(
                    new VideoObservation(
                        "", null, "PUBLIC", "Test ＣＯＶＥＲ", null, null, null, false, null),
                    true)
                .suggestedType())
        .isEqualTo("COVER");
    assertThat(
            rules
                .decide(
                    new VideoObservation(
                        "", null, "PUBLIC", "Cover (a+)+", null, null, null, false, null),
                    true)
                .disposition())
        .isEqualTo("EXCLUDED");
  }

  @Test
  void duplicatesAfterNormalizationAndOversizedConfigurationAreRejected() {
    assertThatThrownBy(
            () ->
                new RuleConfiguration(
                        List.of(
                            new RuleConfiguration.Marker("COVER", RuleConfiguration.Match.WORD),
                            new RuleConfiguration.Marker("ｃｏｖｅｒ", RuleConfiguration.Match.PHRASE)),
                        List.of(),
                        List.of())
                    .validated())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RuleConfiguration(
                        Collections.nCopies(
                            31,
                            new RuleConfiguration.Marker("cover", RuleConfiguration.Match.WORD)),
                        List.of(),
                        List.of())
                    .validated())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RuleConfiguration(
                        List.of(new RuleConfiguration.Marker("　", RuleConfiguration.Match.PHRASE)),
                        List.of(),
                        List.of())
                    .validated())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
