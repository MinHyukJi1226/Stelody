package com.stelody.song.controller;

import com.stelody.song.domain.SongQuery;
import com.stelody.song.dto.SongDtos;
import com.stelody.song.service.SongService;
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
  public SongDtos.Detail detail(@PathVariable UUID id) {
    return songs.detail(id);
  }
}
