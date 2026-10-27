package com.stelody.review.domain;

import com.stelody.catalog.domain.SearchText;
import java.net.URI;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Literal credit evidence; descriptions are data and never executable instructions. */
public final class OriginalCredits {
  private OriginalCredits() {}

  public record Video(
      String id, String channelId, String channelTitle, String title, String description) {}

  public record Hint(
      List<String> artists,
      String videoId,
      boolean conflicting,
      boolean explicitArtists,
      List<String> creditTexts) {}

  public record Evidence(
      String title, List<String> artists, String videoId, String sourceUrl, String method) {}

  private static final Pattern LABEL =
      Pattern.compile(
          "(?iu)^[\\s*#・▶▷▸-]*(original(?:\\s+(?:song|artist|singer|music|link))?|원곡(?:자|가수|링크)?|본가|本家(?:様)?|原曲|原唱)\\s*(?:[:：|│=-]\\s*(.*))?$");
  private static final Pattern URL =
      Pattern.compile("https?://[^\\s<>\\[\\]「」]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern OTHER_CREDIT =
      Pattern.compile(
          "(?iu)^(?:vocal|cover|mix(?:ing)?|master(?:ing)?|illustration|illust|movie|video|arrang(?:e|ement)|inst(?:rumental)?|보컬|믹스|일러스트|영상|편곡)\\b.*");
  private static final Pattern REINTERPRETATION =
      Pattern.compile(
          "(?iu)(?:\\b(?:cover|covered|remix|live|shorts|karaoke|instrumental|lyrics)\\b|歌ってみた|カバー|가사|노래방)");

  public static String text(String value) {
    return value == null
        ? ""
        : Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("(?U)[\\s\\p{Z}]+", " ")
            .strip();
  }

  public static Hint hint(String description) {
    var ids = new LinkedHashSet<String>();
    var credits = new ArrayList<String>();
    var artists = new LinkedHashSet<String>();
    var declarations = new HashSet<List<String>>();
    var explicit = new LinkedHashSet<String>();
    var explicitDeclarations = new HashSet<List<String>>();
    var lines = (description == null ? "" : description).split("\\R");
    for (int i = 0; i < lines.length; i++) {
      var label = LABEL.matcher(text(lines[i]));
      if (!label.matches()) continue;
      String value = Objects.toString(label.group(2), "");
      var block = new ArrayList<String>();
      if (!value.isBlank()) block.add(value);
      for (int j = i + 1; j < Math.min(lines.length, i + 4); j++) {
        String next = text(lines[j]);
        if (next.isBlank()) continue;
        if (LABEL.matcher(next).matches() || OTHER_CREDIT.matcher(next).matches()) break;
        if (URL.matcher(next).find()) block.add(next);
        else break;
      }
      for (String line : block) {
        var urls = URL.matcher(line);
        while (urls.find()) youtubeId(urls.group()).ifPresent(ids::add);
      }
      String name = URL.matcher(value).replaceAll("").replaceAll("[\\s:|・▶▷-]+$", "").strip();
      if (!name.isBlank()) credits.add(name);
      // Generic Original values may be song titles; only explicit artist labels stand alone.
      if (!name.contains(" / ") && !name.contains(" - ") && !name.contains("→")) {
        var names = artistNames(name);
        if (!names.isEmpty()) {
          artists.addAll(names);
          var declaration = names.stream().map(SearchText::searchKey).sorted().toList();
          declarations.add(declaration);
          if (label.group(1).matches("(?iu).*(?:artist|singer|원곡자|원곡가수|原唱)$")) {
            explicit.addAll(names);
            explicitDeclarations.add(declaration);
          }
        }
      }
    }
    boolean named = !explicit.isEmpty();
    var chosen = named ? explicit : artists;
    return new Hint(
        List.copyOf(chosen),
        ids.size() == 1 ? ids.iterator().next() : null,
        ids.size() > 1
            || chosen.size() > 10
            || (named ? explicitDeclarations : declarations).size() > 1,
        named,
        List.copyOf(credits));
  }

  public static Optional<String> youtubeId(String value) {
    try {
      URI uri = URI.create(value.replaceAll("[).,;]+$", ""));
      if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          || uri.getUserInfo() != null
          || uri.getPort() != -1) return Optional.empty();
      String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT), id = null;
      if (host.equals("youtu.be")) id = uri.getPath().substring(1);
      else if (Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
          .contains(host)) {
        if ("/watch".equals(uri.getPath())) {
          for (String part : Objects.toString(uri.getRawQuery(), "").split("&"))
            if (part.startsWith("v=")) {
              if (id != null) return Optional.empty();
              id = part.substring(2);
            }
        } else if (uri.getPath().startsWith("/shorts/") || uri.getPath().startsWith("/embed/"))
          id = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
      }
      return id != null && id.matches("[A-Za-z0-9_-]{11}") ? Optional.of(id) : Optional.empty();
    } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
      return Optional.empty();
    }
  }

  public static String url(String id) {
    return "https://www.youtube.com/watch?v=" + id;
  }

  public static List<String> titles(String source) {
    String title = cleanTitle(source).replaceFirst("(?iu)\\s+covered\\s+by\\s+.*$", "");
    int slash = title.lastIndexOf(" / ");
    if (slash >= 0) title = title.substring(0, slash).strip();
    var result = new LinkedHashSet<String>();
    int dash = title.indexOf(" - ");
    if (dash > 0) result.add(title.substring(dash + 3).strip());
    result.add(title);
    var japanese =
        Pattern.compile(
                "[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}][\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}ー\\s]*")
            .matcher(result.iterator().next());
    while (japanese.find()) {
      String variant = japanese.group().strip();
      if (variant.length() >= 2) result.add(variant);
    }
    return result.stream().filter(OriginalCredits::validTitle).toList();
  }

  public static String cleanTitle(String value) {
    return text(value)
        .replaceAll(
            "(?iu)[\\[【(](?:official[^\\]】)]*|music video|mv|audio|original[^\\]】)]*|オリジナル[^\\]】)]*|4k|8k|hd|cover)[\\]】)]",
            "")
        .replaceFirst("^【(?:IA|初音ミク|鏡音リン|鏡音レン|GUMI)】\\s*", "")
        .strip();
  }

  public static boolean validTitle(String value) {
    return !value.isBlank() && value.length() <= 300 && SearchText.normalize(value).length() <= 400;
  }

  public static List<String> artistNames(String value) {
    String name = text(value);
    if (name.isBlank()
        || name.contains(":")
        || name.contains("http")
        || name.contains("@")
        || name.contains("\\")
        || name.contains("#")) return List.of();
    var names =
        Arrays.stream(name.split("\\s+(?:&|×|x|feat\\.)\\s+|\\s*[,、]\\s*"))
            .map(String::strip)
            .distinct()
            .toList();
    if (names.size() > 10
        || names.stream()
            .anyMatch(
                n -> n.isBlank() || n.length() > 200 || SearchText.normalize(n).length() > 300))
      return List.of();
    return names;
  }

  /** A direct original credit may identify a work without identifying its artist. */
  public static Optional<Evidence> direct(Video video, Hint hint, String sourceId) {
    return direct(video, hint, sourceId, List.of());
  }

  public static Optional<Evidence> direct(
      Video video, Hint hint, String sourceId, List<String> knownTitles) {
    if (hint.conflicting()
        || video.id().equals(sourceId)
        || REINTERPRETATION.matcher(video.title()).find()) return Optional.empty();
    var parsed = identified(video);
    var compound = compoundCredit(hint.creditTexts(), knownTitles);
    String title =
        parsed
            .map(Evidence::title)
            .orElseGet(
                () -> compound.map(NamedTitle::title).orElseGet(() -> cleanTitle(video.title())));
    var artists =
        parsed
            .map(Evidence::artists)
            .orElseGet(() -> compound.map(NamedTitle::artists).orElse(List.of()));
    if (hint.explicitArtists()) artists = hint.artists();
    else if (!hint.artists().isEmpty()) {
      String channelArtist =
          text(video.channelTitle())
              .replaceFirst(
                  "(?iu)\\s+(?:official(?:\\s+(?:youtube\\s+)?channel)?|公式(?:チャンネル)?|오피셜(?:\\s*채널)?)$",
                  "");
      if (hint.artists().size() == 1
          && SearchText.searchKey(hint.artists().getFirst())
              .equals(SearchText.searchKey(channelArtist))) artists = hint.artists();
    }
    return validTitle(title)
        ? Optional.of(new Evidence(title, artists, video.id(), url(sourceId), "DESCRIPTION"))
        : Optional.empty();
  }

  private record NamedTitle(String title, List<String> artists) {}

  private static Optional<NamedTitle> compoundCredit(List<String> credits, List<String> titles) {
    var matches = new ArrayList<NamedTitle>();
    for (String credit : credits) {
      for (String separator : List.of(" - ", " / ", " – ", " — ")) {
        int index = credit.indexOf(separator);
        if (index <= 0) continue;
        String left = credit.substring(0, index).strip(),
            right = credit.substring(index + separator.length()).strip();
        for (String title : titles) {
          String name =
              SearchText.searchKey(right).equals(SearchText.searchKey(title))
                  ? left
                  : SearchText.searchKey(left).equals(SearchText.searchKey(title)) ? right : null;
          if (name != null && !artistNames(name).isEmpty())
            matches.add(new NamedTitle(title, artistNames(name)));
        }
      }
    }
    var unique = matches.stream().distinct().toList();
    return unique.size() == 1 ? Optional.of(unique.getFirst()) : Optional.empty();
  }

  /** Search needs artist/channel agreement or a distributor-generated Topic credit. */
  public static Optional<Evidence> searched(
      Video video, List<String> titles, List<String> expectedArtists) {
    if (REINTERPRETATION.matcher(video.title()).find()) return Optional.empty();
    return identified(video)
        .filter(
            e ->
                titles.stream()
                    .anyMatch(t -> SearchText.searchKey(t).equals(SearchText.searchKey(e.title()))))
        .filter(
            e ->
                expectedArtists.isEmpty()
                    || expectedArtists.stream()
                        .allMatch(
                            a ->
                                e.artists().stream()
                                    .anyMatch(
                                        b ->
                                            SearchText.searchKey(a)
                                                .equals(SearchText.searchKey(b)))));
  }

  private static Optional<Evidence> identified(Video video) {
    String title = cleanTitle(video.title()), channel = text(video.channelTitle());
    boolean topic =
        channel.endsWith(" - Topic")
            && video.description().contains("Auto-generated by YouTube.")
            && video.description().contains("Provided to YouTube by");
    if (topic) {
      var names = artistNames(channel.substring(0, channel.length() - 8));
      if (!names.isEmpty() && validTitle(title))
        return Optional.of(new Evidence(title, names, video.id(), url(video.id()), "SEARCH"));
    }
    String artist =
        channel
            .replaceFirst(
                "(?iu)\\s+(?:official(?:\\s+(?:youtube\\s+)?channel)?|公式(?:チャンネル)?|오피셜(?:\\s*채널)?)$",
                "")
            .strip();
    boolean officialSuffix = !artist.equals(channel);
    var bracket = Pattern.compile("^[【\\[]([^】\\]]+)[】\\]]\\s*(.+)$").matcher(title);
    if (bracket.matches()
        && SearchText.searchKey(bracket.group(1)).equals(SearchText.searchKey(artist))) {
      var credited = artistNames(artist);
      String song = bracket.group(2).strip();
      if (!credited.isEmpty() && validTitle(song))
        return Optional.of(new Evidence(song, credited, video.id(), url(video.id()), "SEARCH"));
    }
    for (String separator : List.of(" - ", " / ", " – ", " — ")) {
      int index = title.indexOf(separator);
      if (index <= 0) continue;
      String left = title.substring(0, index).strip(),
          right = title.substring(index + separator.length()).strip();
      String song =
          SearchText.searchKey(left).equals(SearchText.searchKey(artist))
              ? right
              : SearchText.searchKey(right).equals(SearchText.searchKey(artist)) ? left : null;
      var names = artistNames(artist);
      if (song != null && validTitle(song) && !names.isEmpty())
        return Optional.of(new Evidence(song, names, video.id(), url(video.id()), "SEARCH"));
    }
    var names = artistNames(artist);
    return officialSuffix && !names.isEmpty() && validTitle(title)
        ? Optional.of(new Evidence(title, names, video.id(), url(video.id()), "SEARCH"))
        : Optional.empty();
  }

  public static Optional<Evidence> unique(List<Evidence> candidates) {
    var groups = new HashSet<String>();
    for (var candidate : candidates)
      groups.add(
          SearchText.searchKey(candidate.title())
              + ":"
              + candidate.artists().stream().map(SearchText::searchKey).sorted().toList());
    return groups.size() == 1
        ? candidates.stream().min(Comparator.comparing(Evidence::videoId))
        : Optional.empty();
  }
}
