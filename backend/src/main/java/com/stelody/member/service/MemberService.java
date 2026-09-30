package com.stelody.member.service;

import com.stelody.catalog.web.CatalogException;
import com.stelody.member.dto.MemberDtos;
import com.stelody.member.repository.MemberRepository;
import com.stelody.member.repository.MemberRepository.Position;
import com.stelody.song.domain.SongQuery;
import com.stelody.song.dto.SongDtos;
import com.stelody.song.service.SongService;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MemberService {
  private final MemberRepository members;
  private final SongService songs;
  private final ObjectMapper mapper;

  public MemberService(MemberRepository members, SongService songs, ObjectMapper mapper) {
    this.members = members;
    this.songs = songs;
    this.mapper = mapper;
  }

  public MemberDtos.Page list(String status, int size, String cursor) {
    if (!List.of("ALL", "ACTIVE", "GRADUATED").contains(status) || size < 1 || size > 50)
      throw CatalogException.invalid();
    Position position = null;
    if (cursor != null) {
      try {
        if (cursor.isEmpty() || cursor.length() > 2048) throw CatalogException.invalid();
        position = mapper.readValue(Base64.getUrlDecoder().decode(cursor), Position.class);
        if (position.version() != 1
            || !status.equals(position.filter())
            || position.id() == null
            || position.searchName() == null
            || position.searchName().length() > 200
            || position.activityOrder() < 1
            || position.activityOrder() > 2) throw CatalogException.invalid();
      } catch (RuntimeException exception) {
        throw CatalogException.invalid();
      }
    }
    var rows = members.list(status, size, position);
    boolean more = rows.size() > size;
    var page = more ? rows.subList(0, size) : rows;
    String next = null;
    if (more) {
      var last = page.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  mapper.writeValueAsBytes(
                      new Position(
                          1, status, last.activityOrder(), last.searchName(), last.card().id())));
    }
    return new MemberDtos.Page(page.stream().map(MemberRepository.Row::card).toList(), next, more);
  }

  public MemberDtos.Detail detail(UUID id) {
    var card = members.find(id).orElseThrow(CatalogException::missing);
    return new MemberDtos.Detail(card, members.channels(id));
  }

  public SongDtos.Page songs(
      UUID id, String type, boolean collaboration, String sort, int size, String cursor) {
    members.find(id).orElseThrow(CatalogException::missing);
    return songs.list(
        SongQuery.create(null, type, null, null, collaboration, sort, size, "BROWSE", id), cursor);
  }
}
