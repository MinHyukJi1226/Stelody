package com.stelody.song.service;

import com.stelody.catalog.web.CatalogException;
import com.stelody.song.domain.*;
import com.stelody.song.dto.SongDtos;
import com.stelody.song.repository.SongGroupRepository;
import com.stelody.song.repository.SongRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SongGroupService {
  @Schema(requiredProperties = {"id", "name", "songs"})
  public record Group(String id, String name, SongDtos.Page songs) {}

  @Schema(requiredProperties = {"items", "nextCursor", "hasNext"})
  public record Page(
      List<Group> items, @Schema(nullable = true) String nextCursor, boolean hasNext) {}

  private final SongGroupRepository groups;
  private final SongRepository songs;
  private final SongGroupCursor cursors;
  private final SongCursor songCursors;

  public SongGroupService(
      SongGroupRepository groups,
      SongRepository songs,
      SongGroupCursor cursors,
      SongCursor songCursors) {
    this.groups = groups;
    this.songs = songs;
    this.cursors = cursors;
    this.songCursors = songCursors;
  }

  private SongQuery query(String groupBy, String sort, int size) {
    if (groupBy == null
        || !List.of("TYPE", "MEMBER", "ORIGINAL_ARTIST").contains(groupBy)
        || sort == null
        || !List.of("LATEST", "OLDEST", "VIEWS").contains(sort)) throw CatalogException.invalid();
    return SongQuery.create(null, null, null, null, false, sort, size, "BROWSE", null);
  }

  public Page list(String groupBy, String sort, int size, int songSize, String cursor) {
    var query = query(groupBy, sort, songSize);
    if (size < 1 || size > 10 || songSize > 20) throw CatalogException.invalid();
    UUID publication = songs.publication();
    var token = cursors.decode(cursor, "GROUPS", groupBy, sort, null, publication);
    var found = groups.list(groupBy, size, token == null ? null : token.position());
    boolean more = found.size() > size;
    var page = more ? found.subList(0, size) : found;
    var rows = new LinkedHashMap<String, List<SongRepository.Row>>();
    var counts = new HashMap<String, Long>();
    var unique = new LinkedHashMap<UUID, SongRepository.Row>();
    for (var group : page) {
      var entries = songs.groupSongs(query, publication, null, groupBy, group.id());
      entries = entries.subList(0, Math.min(entries.size(), songSize));
      rows.put(group.id(), entries);
      counts.put(group.id(), songs.groupCount(query, publication, groupBy, group.id()));
      entries.forEach(row -> unique.putIfAbsent(row.id(), row));
    }
    var cards = new HashMap<UUID, SongDtos.Card>();
    songs.cards(List.copyOf(unique.values())).forEach(card -> cards.put(card.id(), card));
    var result = new ArrayList<Group>();
    for (var group : page) {
      var entries = rows.get(group.id());
      long total = counts.get(group.id());
      boolean next = total > entries.size();
      result.add(
          new Group(
              group.id(),
              group.name(),
              new SongDtos.Page(
                  entries.stream().map(row -> cards.get(row.id())).toList(),
                  next
                      ? nextCursor(query, groupBy, group.id(), publication, entries.getLast())
                      : null,
                  next,
                  total)));
    }
    return new Page(
        List.copyOf(result),
        more ? cursors.groups(groupBy, sort, publication, page.getLast().position()) : null,
        more);
  }

  public SongDtos.Page listSongs(
      String groupBy, String groupId, String sort, int size, String cursor) {
    var query = query(groupBy, sort, size);
    String id = groupId(groupBy, groupId);
    UUID publication = songs.publication();
    var token = cursors.decode(cursor, "SONGS", groupBy, sort, id, publication);
    var position =
        songCursors.decode(token == null ? null : token.songCursor(), query, publication);
    long total = songs.groupCount(query, publication, groupBy, id);
    if (total == 0) throw CatalogException.missing();
    var rows = songs.groupSongs(query, publication, position, groupBy, id);
    boolean more = rows.size() > size;
    var page = more ? rows.subList(0, size) : rows;
    return new SongDtos.Page(
        songs.cards(page),
        more ? nextCursor(query, groupBy, id, publication, page.getLast()) : null,
        more,
        total);
  }

  private String nextCursor(
      SongQuery query, String groupBy, String id, UUID publication, SongRepository.Row row) {
    String inner =
        songCursors.encode(
            query, publication, row.rank(), row.viewCount(), row.publishedAt(), row.id());
    return cursors.songs(groupBy, query.sort(), id, publication, inner);
  }

  private String groupId(String groupBy, String id) {
    if (groupBy.equals("TYPE")) {
      if (!List.of("ORIGINAL", "COVER").contains(id)) throw CatalogException.invalid();
      return id;
    }
    if (groupBy.equals("ORIGINAL_ARTIST") && id.equals("OTHERS")) return id;
    try {
      return UUID.fromString(id).toString();
    } catch (IllegalArgumentException e) {
      throw CatalogException.invalid();
    }
  }
}
