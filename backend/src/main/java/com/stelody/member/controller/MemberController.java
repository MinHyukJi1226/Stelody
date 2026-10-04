package com.stelody.member.controller;

import com.stelody.member.dto.MemberDtos;
import com.stelody.member.service.MemberService;
import com.stelody.song.dto.SongDtos;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberController {
  private final MemberService members;

  public MemberController(MemberService members) {
    this.members = members;
  }

  @GetMapping("/api/v1/members")
  @Operation(summary = "멤버 목록")
  public MemberDtos.Page list(
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String cursor) {
    return members.list(status, size, cursor);
  }

  @GetMapping("/api/v1/members/{id}")
  @Operation(summary = "멤버 상세")
  public MemberDtos.Detail detail(@PathVariable UUID id) {
    return members.detail(id);
  }

  @GetMapping("/api/v1/members/{id}/songs")
  @Operation(summary = "멤버의 공개 곡 목록")
  public SongDtos.Page songs(
      @PathVariable UUID id,
      @RequestParam(required = false) String type,
      @RequestParam(defaultValue = "false") boolean collaboration,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String cursor) {
    return members.songs(id, type, collaboration, sort, size, cursor);
  }
}
