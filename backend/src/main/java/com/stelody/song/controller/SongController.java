package com.stelody.song.controller;

import com.stelody.song.domain.SongQuery;
import com.stelody.song.dto.SongDtos;
import com.stelody.song.service.SongService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SongController {
  private final SongService songs;

  public SongController(SongService songs) {
    this.songs = songs;
  }

  @GetMapping("/api/v1/songs")
  @Operation(summary = "공개 곡 검색·목록")
  public SongDtos.Page list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) List<UUID> memberIds,
      @RequestParam(required = false) Integer year,
      @RequestParam(defaultValue = "false") boolean collaboration,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String mode,
      @RequestParam(required = false) String cursor) {
    return songs.list(
        SongQuery.create(q, type, memberIds, year, collaboration, sort, size, mode, null), cursor);
  }

  @GetMapping("/api/v1/songs/{id}")
  @Operation(summary = "공개 곡 상세")
  public SongDtos.Detail detail(@PathVariable UUID id) {
    return songs.detail(id);
  }

  @GetMapping("/api/v1/songs/years")
  @Operation(
      summary = "공개 곡의 검색 연도 선택지",
      description =
          "일반 탐색과 같은 공개 조건을 만족하는 전체 곡의 연도입니다. 대표 영상의 publishedAt을 Asia/Seoul 기준으로 계산하며, 검색어·유형·멤버 등 현재 검색 조건으로 좁히지 않습니다. 로그인 없이 조회할 수 있습니다.")
  public SongDtos.Years years() {
    return songs.years();
  }

  @GetMapping("/api/v1/songs/recommendations")
  @Operation(summary = "중복 없는 무작위 곡 추천")
  public SongDtos.Recommendations recommendations(@RequestParam(defaultValue = "6") int size) {
    return songs.recommendations(size);
  }
}
