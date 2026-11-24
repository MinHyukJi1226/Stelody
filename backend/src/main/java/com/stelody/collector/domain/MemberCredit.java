package com.stelody.collector.domain;

import com.stelody.catalog.domain.SearchText;
import java.util.List;
import java.util.regex.Pattern;

/** Shared credit shape for participant recognition and display-title cleanup. */
final class MemberCredit {
  private MemberCredit() {}

  static String pattern(List<String> memberNames) {
    String names =
        memberNames.stream()
            .map(SearchText::normalize)
            .filter(name -> !name.isEmpty())
            .map(Pattern::quote)
            .reduce((a, b) -> a + "|" + b)
            .orElseThrow();
    // The registered leading name identifies the member. A single parenthesized spelling is
    // presentation text, not another identity lookup; joint-credit checks remain in the caller.
    return "(?:" + names + ")(?:\\s*\\([\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} .’'_-]*\\))?";
  }
}
