package com.stelody.song.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SongRecommendations {
  public record Candidate(UUID id, List<UUID> members) {}

  private SongRecommendations() {}

  public static List<UUID> select(List<Candidate> candidates, int size) {
    return select(candidates, size, ThreadLocalRandom.current());
  }

  static List<UUID> select(List<Candidate> candidates, int size, Random random) {
    var remaining = new ArrayList<>(candidates);
    Collections.shuffle(remaining, random);
    var selected = new ArrayList<UUID>();
    var appearances = new HashMap<UUID, Integer>();
    while (selected.size() < size && !remaining.isEmpty()) {
      int best = 0;
      int lowest = Integer.MAX_VALUE;
      for (int i = 0; i < remaining.size(); i++) {
        int repeats =
            remaining.get(i).members().stream()
                .mapToInt(member -> appearances.getOrDefault(member, 0))
                .sum();
        if (repeats < lowest) {
          best = i;
          lowest = repeats;
          if (repeats == 0) break;
        }
      }
      var candidate = remaining.remove(best);
      selected.add(candidate.id());
      candidate.members().forEach(member -> appearances.merge(member, 1, Integer::sum));
    }
    // Diversity controls selection; cards themselves have no member or date priority.
    Collections.shuffle(selected, random);
    return List.copyOf(selected);
  }
}
