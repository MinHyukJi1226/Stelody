package com.stelody.admin.service;

import com.stelody.admin.domain.*;
import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.repository.CatalogAdminQueries;
import com.stelody.admin.repository.CatalogAdminQueries.Resource;
import com.stelody.admin.web.AdminCatalogException;
import com.stelody.catalog.domain.SearchText;
import com.stelody.review.domain.ReviewItem;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogManagementService {
  private final EntityManager entities;
  private final CatalogAdminQueries queries;
  private final CatalogInputRules rules;

  public CatalogManagementService(
      EntityManager entities, CatalogAdminQueries queries, CatalogInputRules rules) {
    this.entities = entities;
    this.queries = queries;
    this.rules = rules;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page list(String kind, int page, int size, String q) {
    page(page, size);
    if (q.length() > 200) throw AdminCatalogException.invalid();
    var rows = queries.list(Resource.parse(kind), page, size, q);
    return new Page(rows.subList(0, Math.min(size, rows.size())), page, size, rows.size() > size);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Object detail(String kind, UUID id) {
    return queries.detail(Resource.parse(kind), id);
  }

  @Transactional(readOnly = true)
  public AuditPage audits(String kind, UUID id, int page, int size) {
    page(page, size);
    Resource resource = Resource.parse(kind);
    queries.detail(resource, id);
    var rows = queries.audits(resource.name(), id, page, size);
    return new AuditPage(
        rows.subList(0, Math.min(size, rows.size())), page, size, rows.size() > size);
  }

  private void page(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 50) throw AdminCatalogException.invalid();
  }

  private <T extends EditedEntity> T existing(Class<T> type, UUID id, long version) {
    T item = entities.find(type, id);
    if (item == null) throw AdminCatalogException.missing();
    if (item.version() != version) throw AdminCatalogException.conflict();
    return item;
  }

  private void initial(long version) {
    if (version != 0) throw AdminCatalogException.invalid();
  }

  private void flush(EditedEntity value, boolean creating) {
    value.touch();
    if (creating) entities.persist(value);
    entities.flush();
  }

  private List<UUID> reference(UUID id) {
    return id == null ? List.of() : List.of(id);
  }

  @Transactional
  public Saved<Member> member(UUID id, MemberInput input, UUID actor) {
    rules.validate(input);
    rules.text(input.reason());
    rules.aliases(input.aliases(), 300);
    rules.birthday(input.birthdayMonth(), input.birthdayDay());
    boolean create = id == null;
    if (create) {
      initial(input.version());
      id = UUID.randomUUID();
    }
    Object before = create ? null : queries.member(id);
    var item = create ? new ManagedMember(id) : existing(ManagedMember.class, id, input.version());
    item.name(rules.text(input.name()));
    item.searchName(rules.normalized(item.name(), 200));
    item.generation(input.generation());
    item.activityStatus(input.activityStatus().name());
    item.profileImageUrl(rules.url(input.profileImageUrl()));
    item.debutDate(input.debutDate());
    item.birthdayMonth(input.birthdayMonth());
    item.birthdayDay(input.birthdayDay());
    flush(item, create);
    queries.aliases(Resource.MEMBERS, id, input.aliases());
    var after = queries.member(id);
    queries.audit(
        "MEMBERS", id, actor, create ? "CREATE" : "UPDATE", before, after, input.reason());
    return new Saved<>(after, queries.duplicates(Resource.MEMBERS, id, item.searchName()));
  }

  @Transactional
  public Saved<Artist> artist(UUID id, ArtistInput input, UUID actor) {
    rules.validate(input);
    rules.text(input.reason());
    rules.aliases(input.aliases(), 300);
    boolean create = id == null;
    if (create) {
      initial(input.version());
      id = UUID.randomUUID();
    }
    Object before = create ? null : queries.artist(id);
    var item = create ? new ManagedArtist(id) : existing(ManagedArtist.class, id, input.version());
    item.name(rules.text(input.name()));
    item.searchName(rules.normalized(item.name(), 300));
    flush(item, create);
    queries.aliases(Resource.ARTISTS, id, input.aliases());
    var after = queries.artist(id);
    queries.audit(
        "ARTISTS", id, actor, create ? "CREATE" : "UPDATE", before, after, input.reason());
    return new Saved<>(after, queries.duplicates(Resource.ARTISTS, id, item.searchName()));
  }

  @Transactional
  public Saved<Work> work(UUID id, WorkInput input, UUID actor) {
    rules.validate(input);
    rules.text(input.reason());
    rules.aliases(input.aliases());
    rules.ids(input.artistIds());
    rules.extras(input.links(), input.karaoke());
    queries.requireReferences(Resource.ARTISTS, input.artistIds());
    boolean create = id == null;
    if (create) {
      initial(input.version());
      id = UUID.randomUUID();
    }
    Object before = create ? null : queries.work(id);
    var item = create ? new ManagedWork(id) : existing(ManagedWork.class, id, input.version());
    item.title(rules.text(input.title()));
    item.searchTitle(rules.normalized(item.title(), 400));
    flush(item, create);
    queries.aliases(Resource.WORKS, id, input.aliases());
    queries.relation("work_artist", "work_id", "artist_id", id, input.artistIds());
    queries.extras(false, id, input.links(), input.karaoke());
    var after = queries.work(id);
    queries.audit("WORKS", id, actor, create ? "CREATE" : "UPDATE", before, after, input.reason());
    return new Saved<>(after, queries.duplicates(Resource.WORKS, id, item.searchTitle()));
  }

  @Transactional
  public Channel channel(UUID id, ChannelInput input, UUID actor) {
    rules.validate(input);
    rules.text(input.reason());
    queries.requireReferences(Resource.MEMBERS, reference(input.memberId()));
    if (input.memberId() != null && input.channelType() != ChannelType.MEMBER
        || input.channelType() == ChannelType.EXTERNAL && input.collectionEnabled())
      throw AdminCatalogException.invalid();
    boolean create = id == null;
    if (create) {
      initial(input.version());
      id = UUID.randomUUID();
    }
    Object before = create ? null : queries.channel(id);
    var item =
        create ? new ManagedChannel(id) : existing(ManagedChannel.class, id, input.version());
    if (!create
        && (!item.youtubeId().equals(input.youtubeId())
            || !item.channelType().equals(input.channelType().name())))
      throw new AdminCatalogException(
          409, "CHANNEL_IDENTITY_IMMUTABLE", "기존 채널 ID와 유형은 변경할 수 없습니다");
    item.youtubeId(input.youtubeId());
    item.name(rules.text(input.name()));
    item.memberId(input.memberId());
    item.channelType(input.channelType().name());
    item.collectionEnabled(input.collectionEnabled());
    flush(item, create);
    var after = queries.channel(id);
    queries.audit(
        "CHANNELS", id, actor, create ? "CREATE" : "UPDATE", before, after, input.reason());
    return after;
  }

  @Transactional
  public Saved<Song> song(UUID id, SongInput input, UUID actor) {
    rules.validate(input);
    rules.text(input.reason());
    rules.aliases(input.aliases());
    rules.ids(input.memberIds());
    rules.ids(input.externalArtistIds());
    rules.extras(input.links(), input.karaoke());
    queries.requireReferences(Resource.MEMBERS, input.memberIds());
    queries.requireReferences(Resource.ARTISTS, input.externalArtistIds());
    queries.requireReferences(Resource.WORKS, reference(input.workId()));
    if (input.isSpecialEvent()
        && (input.specialEventLabel() == null
            || SearchText.normalize(input.specialEventLabel()).isEmpty()))
      throw AdminCatalogException.invalid();
    if (input.searchVisibility() == SearchVisibility.DIFFICULT
        && (input.recommendedSearchQuery() == null
            || SearchText.normalize(input.recommendedSearchQuery()).isEmpty()))
      throw AdminCatalogException.invalid();
    boolean create = id == null;
    if (create) {
      initial(input.version());
      id = UUID.randomUUID();
    }
    Object before = create ? null : queries.song(id);
    var item = create ? new ManagedSong(id) : existing(ManagedSong.class, id, input.version());
    // Flush the aggregate version before replacing child rows; concurrent writers cannot both
    // commit.
    item.title(rules.text(input.title()));
    item.searchTitle(rules.normalized(item.title(), 400));
    item.songType(input.type().name());
    item.workId(input.workId());
    item.visibility(input.visibility().name());
    if (input.representativeVideoId() != null
        && !queries.video(input.representativeVideoId()).songId().equals(id))
      throw AdminCatalogException.invalid();
    item.representativeVideoId(input.representativeVideoId());
    item.isSpecialEvent(input.isSpecialEvent());
    item.specialEventLabel(input.isSpecialEvent() ? rules.text(input.specialEventLabel()) : null);
    if (input.searchVisibility().name().equals(item.searchVisibility())
        && Objects.equals(input.recommendedSearchQuery(), item.recommendedSearchQuery())) {
      // Preserve the original confirmation time when unrelated fields are edited.
    } else
      item.searchCheckedAt(
          input.searchVisibility() == SearchVisibility.UNCHECKED ? null : Instant.now());
    item.searchVisibility(input.searchVisibility().name());
    item.recommendedSearchQuery(
        input.searchVisibility() == SearchVisibility.UNCHECKED
            ? null
            : input.recommendedSearchQuery() == null
                ? null
                : rules.text(input.recommendedSearchQuery()));
    flush(item, create);
    queries.aliases(Resource.SONGS, id, input.aliases());
    queries.relation("song_member", "song_id", "member_id", id, input.memberIds());
    queries.relation("song_external_artist", "song_id", "artist_id", id, input.externalArtistIds());
    queries.extras(true, id, input.links(), input.karaoke());
    validateSong(item, input.memberIds());
    var after = queries.song(id);
    queries.audit("SONGS", id, actor, create ? "CREATE" : "UPDATE", before, after, input.reason());
    return new Saved<>(after, queries.duplicates(Resource.SONGS, id, item.searchTitle()));
  }

  private void validateSong(ManagedSong item, List<UUID> members) {
    queries.lockVideos(item.id());
    var videos = queries.videos(item.id());
    var representative =
        videos.stream()
            .filter(v -> v.id().equals(item.representativeVideoId()))
            .findFirst()
            .orElse(null);
    if (item.representativeVideoId() != null && representative == null)
      throw AdminCatalogException.invalid();
    if (!"PUBLISHED".equals(item.visibility())) return;
    if (members.isEmpty()
        || representative == null
        || !"PUBLIC".equals(representative.availability())
        || representative.sourceObservedAt() == null
        || !representative.sourceObservedAt().isAfter(Instant.now().minusSeconds(2592000))
        || (representative.publishedAt() == null && representative.sourcePublishedAt() == null)
        || (representative.publishedAt() != null
                ? representative.publishedAt()
                : representative.sourcePublishedAt())
            .isAfter(Instant.now()))
      throw new AdminCatalogException(
          400, "SONG_NOT_PUBLISHABLE", "공개 가능한 대표 영상과 확인된 참여 멤버가 필요합니다");
    boolean validKind =
        "COVER".equals(item.songType())
            ? "OFFICIAL_COVER".equals(representative.kind())
            : "OFFICIAL_MV".equals(representative.kind())
                || "AUDIO".equals(representative.kind())
                    && videos.stream().noneMatch(v -> "OFFICIAL_MV".equals(v.kind()));
    if (!validKind)
      throw new AdminCatalogException(
          400, "INVALID_REPRESENTATIVE_VIDEO", "공식 MV·음원·커버 대표 영상 기준을 확인해 주세요");
  }

  @Transactional
  public Video register(UUID reviewId, Registration input, UUID actor) {
    rules.validate(input);
    rules.text(input.reason());
    var song = existing(ManagedSong.class, input.songId(), input.songVersion());
    Object oldSong = queries.song(song.id());
    var review = entities.find(ReviewItem.class, reviewId);
    if (review == null) throw AdminCatalogException.missing();
    if (review.version() != input.version()) throw AdminCatalogException.conflict();
    if (!"PENDING".equals(review.status()))
      throw new AdminCatalogException(409, "REVIEW_NOT_PENDING", "보류 중인 후보만 등록할 수 있습니다");
    var source = queries.candidate(reviewId);
    queries.lockChannel(source.channelId());
    var channel = queries.channel(source.channelId());
    if (!channel.collectionEnabled()
        || "EXTERNAL".equals(channel.channelType())
        || !"PUBLIC".equals(source.availability())
        || source.title() == null
        || source.title().isBlank()
        || source.publishedAt() == null
        || source.publishedAt().isAfter(Instant.now())
        || !source.observedAt().isAfter(Instant.now().minusSeconds(2592000)))
      throw new AdminCatalogException(
          409, "CANDIDATE_REQUIRES_REFRESH", "허용된 공식 채널의 최신 공개 후보를 확인해 주세요");
    if (queries.registeredSong(source.youtubeId()).isPresent())
      throw new AdminCatalogException(409, "VIDEO_ALREADY_REGISTERED", "이미 등록된 영상은 기존 곡에서 확인해 주세요");
    song.touch();
    entities.flush();
    UUID video = queries.insertVideo(song.id(), source, input.kind());
    review.register(video, input.reason().strip(), Instant.now());
    entities.flush();
    validateSong(song, queries.song(song.id()).memberIds());
    var after = queries.video(video);
    queries.audit(
        "REVIEWS",
        reviewId,
        actor,
        "REGISTER",
        Map.of("status", "PENDING", "version", input.version()),
        Map.of("status", "REGISTERED", "videoId", video, "songId", song.id()),
        input.reason());
    queries.audit(
        "SONGS",
        song.id(),
        actor,
        "ATTACH_VIDEO",
        oldSong,
        queries.song(song.id()),
        input.reason());
    return after;
  }

  @Transactional
  public Video registerUrl(UUID songId, VideoRegistration input, UUID actor) {
    rules.validate(input);
    rules.text(input.reason());
    String youtube = rules.youtubeId(input.videoUrl());
    return register(
        queries.candidateId(youtube),
        new Registration(
            input.reviewVersion(), songId, input.version(), input.kind(), input.reason()),
        actor);
  }

  @Transactional
  public Video video(UUID songId, UUID videoId, long songVersion, VideoInput input, UUID actor) {
    rules.validate(input);
    rules.text(input.reason());
    if (songVersion < 0) throw AdminCatalogException.invalid();
    var song = existing(ManagedSong.class, songId, songVersion);
    Object oldSong = queries.song(songId);
    var before = queries.video(videoId);
    if (!before.songId().equals(songId)) throw AdminCatalogException.missing();
    song.touch();
    entities.flush();
    var video = existing(ManagedVideo.class, videoId, input.version());
    video.videoKind(input.kind().name());
    video.publishedAt(input.publishedAt());
    video.thumbnailUrl(rules.url(input.thumbnailUrl()));
    video.touch();
    entities.flush();
    validateSong(song, queries.song(songId).memberIds());
    var after = queries.video(videoId);
    queries.audit("VIDEOS", videoId, actor, "UPDATE", before, after, input.reason());
    queries.audit(
        "SONGS", songId, actor, "EDIT_VIDEO", oldSong, queries.song(songId), input.reason());
    return after;
  }
}
