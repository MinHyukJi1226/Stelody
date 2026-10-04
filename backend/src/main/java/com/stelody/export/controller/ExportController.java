package com.stelody.export.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.export.dto.ExportDtos;
import com.stelody.export.service.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ExportController {
  private final ExportService exports;
  private final YouTubeConnectionService connections;

  public ExportController(ExportService exports, YouTubeConnectionService connections) {
    this.exports = exports;
    this.connections = connections;
  }

  @GetMapping("/api/v1/me/youtube/connection")
  @Operation(summary = "YouTube 연결 상태")
  public ExportDtos.Connection connection(@AuthenticationPrincipal SessionUser user) {
    return connections.status(user.id());
  }

  @PostMapping("/api/v1/me/youtube/authorizations")
  @Operation(summary = "YouTube 추가 동의 시작")
  public ExportDtos.Authorization authorize(
      @AuthenticationPrincipal SessionUser user, HttpServletRequest request) {
    return connections.authorize(user.id(), request.getSession().getId());
  }

  @GetMapping("/api/v1/me/youtube/callback")
  @Operation(summary = "YouTube 추가 동의 콜백")
  public ResponseEntity<Void> callback(
      @AuthenticationPrincipal SessionUser user,
      HttpServletRequest request,
      @RequestParam String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error) {
    connections.callback(user.id(), request.getSession().getId(), state, code, error);
    return ResponseEntity.status(303)
        .location(URI.create("/api/v1/me/youtube/connection"))
        .header("Referrer-Policy", "no-referrer")
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .build();
  }

  @DeleteMapping("/api/v1/me/youtube/connection")
  @Operation(summary = "YouTube 연결 해제")
  public ExportDtos.Connection disconnect(@AuthenticationPrincipal SessionUser user) {
    return connections.disconnect(user.id());
  }

  @PostMapping("/api/v1/me/playlists/{id}/youtube-exports")
  @Operation(summary = "새 비공개 YouTube 목록으로 내보내기 접수")
  public ResponseEntity<ExportDtos.Job> create(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @Valid @RequestBody ExportDtos.Create request) {
    var job = exports.create(user.id(), id, request);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/me/youtube-exports/" + job.id()))
        .body(job);
  }

  @GetMapping("/api/v1/me/youtube-exports/{id}")
  @Operation(summary = "내보내기 진행 상태")
  public ExportDtos.Job job(@AuthenticationPrincipal SessionUser user, @PathVariable UUID id) {
    return exports.job(user.id(), id);
  }

  @PostMapping("/api/v1/me/youtube-exports/{id}/retry")
  @Operation(summary = "실패한 내보내기 재시도")
  public ExportDtos.Job retry(@AuthenticationPrincipal SessionUser user, @PathVariable UUID id) {
    return exports.retry(user.id(), id);
  }

  @PostMapping("/api/v1/me/youtube-exports/{id}/cancel")
  @Operation(summary = "내보내기 취소")
  public ExportDtos.Job cancel(@AuthenticationPrincipal SessionUser user, @PathVariable UUID id) {
    return exports.cancel(user.id(), id);
  }
}
