package com.stelody.admin.controller;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.service.CatalogManagementService;
import com.stelody.auth.domain.SessionUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class CatalogAdminController {
  private final CatalogManagementService service;

  public CatalogAdminController(CatalogManagementService service) {
    this.service = service;
  }

  private <T> ResponseEntity<T> response(T body, boolean created) {
    return ResponseEntity.status(created ? 201 : 200)
        .cacheControl(CacheControl.noStore())
        .body(body);
  }

  @GetMapping({"/members", "/artists", "/works", "/channels"})
  @Operation(summary = "카탈로그 관리 목록")
  public ResponseEntity<Page> list(
      jakarta.servlet.http.HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String q) {
    String path = request.getRequestURI();
    return response(service.list(path.substring(path.lastIndexOf('/') + 1), page, size, q), false);
  }

  @GetMapping("/songs")
  @Operation(
      summary = "관리자 곡 목록",
      description =
          "자동 등록·수동 등록 및 모든 노출 상태의 곡을 조회합니다. 곡 제목·별칭 검색과 제목·ID 정렬, page·size·hasNext를 유지합니다. status는 노출 설정, informationComplete는 현재 정보 완성도입니다. discoveredAt은 현재 연결된 영상의 최초 발견 기록이며 공개일·등록일이 아닙니다.")
  public ResponseEntity<SongPage> songs(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String q) {
    return response(service.songs(page, size, q), false);
  }

  @GetMapping({"/members/{id}", "/artists/{id}", "/works/{id}", "/songs/{id}", "/channels/{id}"})
  @Operation(summary = "카탈로그 관리 상세")
  public ResponseEntity<Object> detail(
      jakarta.servlet.http.HttpServletRequest request, @PathVariable UUID id) {
    String[] path = request.getRequestURI().split("/");
    return response(service.detail(path[path.length - 2], id), false);
  }

  @GetMapping({
    "/members/{id}/audit",
    "/artists/{id}/audit",
    "/works/{id}/audit",
    "/songs/{id}/audit",
    "/channels/{id}/audit",
    "/videos/{id}/audit",
    "/reviews/{id}/audit"
  })
  @Operation(summary = "카탈로그 변경 이력")
  public ResponseEntity<AuditPage> audit(
      jakarta.servlet.http.HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    String[] path = request.getRequestURI().split("/");
    return response(service.audits(path[path.length - 3], id, page, size), false);
  }

  @PostMapping("/members")
  @Operation(summary = "멤버 등록")
  public ResponseEntity<Saved<Member>> createMember(
      @Valid @RequestBody MemberInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.member(null, body, user.id()), true);
  }

  @PutMapping("/members/{id}")
  @Operation(summary = "멤버 수정")
  public ResponseEntity<Saved<Member>> updateMember(
      @PathVariable UUID id,
      @Valid @RequestBody MemberInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.member(id, body, user.id()), false);
  }

  @PostMapping("/artists")
  @Operation(summary = "외부 아티스트 등록")
  public ResponseEntity<Saved<Artist>> createArtist(
      @Valid @RequestBody ArtistInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.artist(null, body, user.id()), true);
  }

  @PutMapping("/artists/{id}")
  @Operation(summary = "외부 아티스트 수정")
  public ResponseEntity<Saved<Artist>> updateArtist(
      @PathVariable UUID id,
      @Valid @RequestBody ArtistInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.artist(id, body, user.id()), false);
  }

  @PostMapping("/works")
  @Operation(summary = "원곡 등록")
  public ResponseEntity<Saved<Work>> createWork(
      @Valid @RequestBody WorkInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.work(null, body, user.id()), true);
  }

  @PutMapping("/works/{id}")
  @Operation(summary = "원곡 수정")
  public ResponseEntity<Saved<Work>> updateWork(
      @PathVariable UUID id,
      @Valid @RequestBody WorkInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.work(id, body, user.id()), false);
  }

  @PostMapping("/channels")
  @Operation(summary = "수집 채널 등록")
  public ResponseEntity<Channel> createChannel(
      @Valid @RequestBody ChannelInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.channel(null, body, user.id()), true);
  }

  @PutMapping("/channels/{id}")
  @Operation(summary = "수집 채널 수정")
  public ResponseEntity<Channel> updateChannel(
      @PathVariable UUID id,
      @Valid @RequestBody ChannelInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.channel(id, body, user.id()), false);
  }

  @PostMapping("/songs")
  @Operation(summary = "곡 등록")
  public ResponseEntity<Saved<Song>> createSong(
      @Valid @RequestBody SongInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.song(null, body, user.id()), true);
  }

  @PutMapping("/songs/{id}")
  @Operation(summary = "곡 수정")
  public ResponseEntity<Saved<Song>> updateSong(
      @PathVariable UUID id,
      @Valid @RequestBody SongInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.song(id, body, user.id()), false);
  }

  @PostMapping("/reviews/{id}/registration")
  @Operation(summary = "수집 후보를 곡 영상으로 등록")
  public ResponseEntity<Video> register(
      @PathVariable UUID id,
      @Valid @RequestBody Registration body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.register(id, body, user.id()), true);
  }

  @PostMapping("/songs/{id}/videos")
  @Operation(summary = "곡의 추가 영상 등록")
  public ResponseEntity<Video> registerUrl(
      @PathVariable UUID id,
      @Valid @RequestBody VideoRegistration body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.registerUrl(id, body, user.id()), true);
  }

  @PutMapping("/songs/{id}/videos/{videoId}")
  @Operation(summary = "곡 영상 정보 수정")
  public ResponseEntity<Video> video(
      @PathVariable UUID id,
      @PathVariable UUID videoId,
      @RequestParam long songVersion,
      @Valid @RequestBody VideoInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.video(id, videoId, songVersion, body, user.id()), false);
  }
}
