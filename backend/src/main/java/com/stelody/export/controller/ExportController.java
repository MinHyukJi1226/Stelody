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
  private final com.stelody.export.web.YouTubeOAuthReturn returns;

  public ExportController(
      ExportService exports,
      YouTubeConnectionService connections,
      com.stelody.export.web.YouTubeOAuthReturn returns) {
    this.exports = exports;
    this.connections = connections;
    this.returns = returns;
  }

  @GetMapping("/api/v1/me/youtube/connection")
  @Operation(summary = "YouTube 연결 상태")
  public ExportDtos.Connection connection(@AuthenticationPrincipal SessionUser user) {
    return connections.status(user.id());
  }

  @PostMapping("/api/v1/me/youtube/authorizations")
  @Operation(
      summary = "YouTube 추가 동의 시작",
      description =
          "선택 JSON 본문 returnTo·callbackClient로 복귀 경로와 콜백을 지정합니다. 생략하면 /library/youtube 및 default를 사용합니다. Google 일반 로그인 선택과 별도이며 반환한 authorizationUrl로 브라우저를 이동합니다.")
  public ExportDtos.Authorization authorize(
      @AuthenticationPrincipal SessionUser user,
      HttpServletRequest request,
      @RequestBody(required = false) ExportDtos.AuthorizationRequest input) {
    return connections.authorize(user.id(), request.getSession().getId(), input);
  }

  @GetMapping({"/api/v1/me/youtube/callback", "/api/v1/me/youtube/callback/local"})
  @Operation(summary = "YouTube 추가 동의 콜백")
  public ResponseEntity<Void> callback(
      @AuthenticationPrincipal SessionUser user,
      HttpServletRequest request,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error) {
    String client = request.getRequestURI().endsWith("/local") ? "local" : "default";
    boolean duplicate =
        java.util.stream.Stream.of("state", "code", "error")
            .anyMatch(
                k ->
                    request.getParameterValues(k) != null
                        && request.getParameterValues(k).length != 1);
    var session = request.getSession(false);
    var result =
        connections.callback(
            user == null ? null : user.id(),
            session == null ? null : session.getId(),
            duplicate ? null : state,
            code,
            error,
            client);
    return ResponseEntity.status(303)
        .location(
            URI.create(
                returns.location(client, result.returnTo(), result.result(), result.errorCode())))
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
  @Operation(
      summary = "새 비공개 YouTube 목록으로 내보내기 접수",
      description =
          "현재 플레이리스트 version과 UUID requestId로 이름·항목 순서의 스냅샷을 접수합니다. 이용 불가 항목은 건너뜁니다. 같은 목록·version·requestId 재전송은 기존 작업을 반환하며 항상 202이고 완료를 의미하지 않습니다. QUEUED·RUNNING·FAILED·UNCERTAIN 작업이 있으면 새 요청은 409 EXPORT_ALREADY_ACTIVE입니다. FAILED·UNCERTAIN은 새 접수 대신 기존 작업의 retry 또는 cancel을 사용합니다.")
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
  @Operation(
      summary = "내보내기 진행 상태",
      description =
          "status와 copiedCount/skippedCount/remainingCount를 확인합니다. SUCCEEDED도 건너뛴 곡이 있을 수 있습니다. UNCERTAIN은 외부 쓰기 결과를 확인하지 못한 상태이며 remainingCount를 미반영 곡 수로 단정하지 않습니다. errorCode는 작업 사유이고 HTTP 오류의 ApiProblem.code와 별개입니다.")
  public ExportDtos.Job job(@AuthenticationPrincipal SessionUser user, @PathVariable UUID id) {
    return exports.job(user.id(), id);
  }

  @PostMapping("/api/v1/me/youtube-exports/{id}/retry")
  @Operation(
      summary = "실패한 내보내기 재시도",
      description =
          "기능이 활성화되고 YouTube가 CONNECTED이면 FAILED·UNCERTAIN을 QUEUED로 돌리고 errorCode를 지웁니다. 복사 완료 항목은 유지하며 같은 작업·외부 목록에서 이어갑니다. UNCERTAIN은 생성 표식이나 외부 항목 순서를 먼저 대조하며 결과가 확인되지 않으면 UNCERTAIN에 남을 수 있습니다. 새 requestId로 같은 작업을 반복하거나 불확실한 쓰기를 무조건 반복하지 않습니다. QUEUED·RUNNING·SUCCEEDED는 변경 없이 반환하고 CANCELLED는 409 EXPORT_NOT_RETRYABLE입니다. HTTP 200은 재접수 응답이며 작업 완료를 의미하지 않습니다.")
  public ExportDtos.Job retry(@AuthenticationPrincipal SessionUser user, @PathVariable UUID id) {
    return exports.retry(user.id(), id);
  }

  @PostMapping("/api/v1/me/youtube-exports/{id}/cancel")
  @Operation(
      summary = "내보내기 취소",
      description =
          "QUEUED·RUNNING·FAILED·UNCERTAIN을 CANCELLED로 변경해 이후 쓰기를 중단합니다. CANCELLED 재취소와 SUCCEEDED 취소는 현재 상태를 그대로 반환합니다. 이미 진행 중인 외부 요청은 반영될 수 있고 생성된 YouTube 목록·곡은 삭제하지 않습니다. youtubeUrl이 있으면 결과를 직접 확인할 수 있습니다. 취소 후 새 내보내기는 별도 요청이며 기존 외부 목록을 정리하지 않습니다.")
  public ExportDtos.Job cancel(@AuthenticationPrincipal SessionUser user, @PathVariable UUID id) {
    return exports.cancel(user.id(), id);
  }
}
