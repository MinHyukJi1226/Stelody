package com.stelody.song.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SongDtos {
  private SongDtos() {}

  public record Participant(UUID id, String name, String kind, String activityStatus) {}

  public record Artist(UUID id, String name) {}

  public record Work(UUID id, String title, List<Artist> artists) {}

  public record Card(
      UUID id,
      String title,
      String type,
      Instant publishedAt,
      String thumbnailUrl,
      List<Participant> participants,
      Work work,
      boolean collaboration,
      boolean isSpecialEvent,
      String specialEventLabel,
      Long viewCount,
      Instant viewsObservedAt) {}

  public record Video(UUID id, String youtubeId, String kind, String url, boolean embeddable) {}

  public record SearchHelp(String visibility, String recommendedQuery, Instant checkedAt) {}

  public record ExternalLink(String platform, String url) {}

  public record Karaoke(String provider, String status, String number) {}

  public record WorkResources(UUID workId, List<ExternalLink> links, List<Karaoke> karaoke) {}

  public record Detail(
      Card song,
      Video representativeVideo,
      SearchHelp searchHelp,
      List<Card> relatedSongs,
      List<ExternalLink> links,
      List<Karaoke> karaoke,
      WorkResources workResources) {}

  public record Page(List<Card> items, String nextCursor, boolean hasNext) {}
}
