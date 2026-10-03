package com.stelody.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stelody.song.domain.SongRecommendations.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SongRecommendationsTest {
  private static final UUID A = id(1), B = id(2), C = id(3);

  private static UUID id(int value) {
    return new UUID(0, value);
  }

  @Test
  void enoughSoloCandidatesRepresentDifferentMembersEvenWithAnUnevenCatalog() {
    var candidates = new ArrayList<Candidate>();
    for (int i = 0; i < 100; i++) candidates.add(new Candidate(id(100 + i), List.of(A)));
    candidates.add(new Candidate(id(200), List.of(B)));
    candidates.add(new Candidate(id(201), List.of(C)));
    var selected = SongRecommendations.select(candidates, 3, new Random(0));
    assertThat(selected).hasSize(3).doesNotHaveDuplicates().contains(id(200), id(201));
  }

  @Test
  void allConfirmedMembersOfACollaborationCountForDiversity() {
    var candidates =
        List.of(
            new Candidate(id(100), List.of(A, B)),
            new Candidate(id(101), List.of(A)),
            new Candidate(id(102), List.of(B)),
            new Candidate(id(103), List.of(C)));
    // Keep this fixture's order so the collaboration is selected first.
    var random =
        new Random(0) {
          @Override
          public int nextInt(int bound) {
            return bound - 1;
          }
        };
    assertThat(SongRecommendations.select(candidates, 2, random))
        .containsExactlyInAnyOrder(id(100), id(103));
  }

  @Test
  void insufficientMemberVarietyStillFillsTheRequestWithoutRepeatedSongs() {
    var candidates =
        List.of(
            new Candidate(id(100), List.of(A)),
            new Candidate(id(101), List.of(A)),
            new Candidate(id(102), List.of(A)));
    assertThat(SongRecommendations.select(candidates, 6, new Random(0)))
        .containsExactlyInAnyOrder(id(100), id(101), id(102));
    assertThat(SongRecommendations.select(candidates, 2, new Random(0)))
        .hasSize(2)
        .doesNotHaveDuplicates();
  }

  @Test
  void additionalSlotsPreferMembersWithFewerAppearances() {
    var candidates = new ArrayList<Candidate>();
    for (int i = 0; i < 30; i++) {
      candidates.add(new Candidate(id(100 + i), List.of(List.of(A, B, C).get(i % 3))));
    }
    var selected = SongRecommendations.select(candidates, 9, new Random(0));
    for (UUID member : List.of(A, B, C)) {
      assertThat(
              candidates.stream()
                  .filter(
                      candidate ->
                          selected.contains(candidate.id())
                              && candidate.members().contains(member)))
          .hasSize(3);
    }
  }

  @Test
  void randomChoiceCanReachTheWholeCatalogAndChangesWithTheRandomSource() {
    var candidates = new ArrayList<Candidate>();
    for (int i = 0; i < 100; i++) candidates.add(new Candidate(id(100 + i), List.of(A)));
    var first = SongRecommendations.select(candidates, 6, new Random(0));
    var second = SongRecommendations.select(candidates, 6, new Random(1));
    assertThat(first)
        .doesNotHaveDuplicates()
        .anyMatch(value -> value.getLeastSignificantBits() < 194);
    assertThat(second).isNotEqualTo(first);
  }

  @Test
  void emptyCatalogReturnsNoRecommendations() {
    assertThat(SongRecommendations.select(List.of(), 6, new Random(0))).isEmpty();
  }
}
