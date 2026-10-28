package com.stelody.admin.controller;

import com.stelody.admin.dto.ArtistRegistrationDtos;
import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.service.ArtistRegistrationService;
import com.stelody.admin.service.CatalogManagementService;
import com.stelody.auth.domain.SessionUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class CatalogAdminController {
  private final CatalogManagementService service;
  private final ArtistRegistrationService registrations;

  public CatalogAdminController(
      CatalogManagementService service, ArtistRegistrationService registrations) {
    this.service = service;
    this.registrations = registrations;
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
  @Operation(
      summary = "외부 아티스트 등록",
      description =
          "응답 유실 복구가 필요하면 등록 전에 프론트가 생성·보관한 UUID를 Idempotency-Key 헤더로 전달합니다. 본문(version=0,name,aliases,reason)은 기존 형식입니다. 최초 성공은 201, 동일한 관리자·요청 ID·본문 재전송은 추가 생성·감사 없이 같은 아티스트 ID의 현재 정보와 duplicates를 200으로 반환합니다. Idempotency-Replayed는 최초 false·재전송 true입니다. 같은 ID의 다른 본문은 409 ARTIST_REGISTRATION_REQUEST_CONFLICT입니다. 처리 중 재전송은 409 ARTIST_REGISTRATION_PROCESSING이며 결과 API를 3초 간격으로 조회합니다. 실패한 요청을 같은 ID로 재실행하지 않습니다. 헤더 생략 시 기존 201 등록 계약을 유지하지만 결과 조회·중복 방지는 제공하지 않습니다. 인증·CSRF·JSON/Bean 검증 실패 등 접수 전 거절은 결과 기록이 없을 수 있으므로 NOT_PROCESSED로 조회됩니다. 저장소 장애는 결과 없음으로 판단하지 않습니다. 아티스트와 COMPLETED 결과 기록은 같은 트랜잭션입니다.")
  public ResponseEntity<Saved<Artist>> createArtist(
      @Valid @RequestBody ArtistInput body,
      @AuthenticationPrincipal SessionUser user,
      @Parameter(
              description =
                  "등록 전 생성한 요청 UUID. 응답 유실 시 같은 값과 본문 전체를 유지합니다. 최초 접수 후 24시간이며 만료된 ID는 다시 사용할 수 없습니다.",
              schema = @Schema(type = "string", format = "uuid"))
          @RequestHeader(name = ArtistRegistrationService.HEADER, required = false)
          String requestId) {
    if (requestId == null) return response(service.artist(null, body, user.id()), true);
    var result = registrations.create(user.id(), requestId, body);
    return ResponseEntity.status(result.replayed() ? 200 : 201)
        .cacheControl(CacheControl.noStore())
        .header(ArtistRegistrationService.REPLAY_HEADER, Boolean.toString(result.replayed()))
        .body(result.saved());
  }

  @GetMapping("/artist-registrations/{requestId}")
  @Operation(
      summary = "아티스트 등록 요청 결과 확인",
      description =
          "등록 전에 보관한 Idempotency-Key UUID로 본인이 접수한 요청만 조회합니다. 로그인한 현재 ADMIN 역할이 필요하며 다른 관리자·미접수 ID는 모두 NOT_PROCESSED이고 등록 정보는 반환하지 않습니다. 읽기 전용이며 등록·재시도·정리를 실행하지 않습니다. NOT_PROCESSED는 본인 접수 기록 없음, PROCESSING은 처리 중(3초 간격 재조회), COMPLETED는 artistId에 생성된 UUID 반환, FAILED는 errorCode로 실패를 안내합니다. NOT_PROCESSED의 createdAt/expiresAt/finishedAt은 null이며 결과 조회 장애·401·403·503과 구분해야 합니다. 완료·실패의 pollAfterSeconds는 null입니다. 결과는 최초 접수부터 24시간이며 조회·재전송으로 연장되지 않고 이후 410 ARTIST_REGISTRATION_RESULT_EXPIRED입니다. 만료 상세 정보는 정리하지만 요청 UUID·본문 해시·소유자·접수/만료 시각의 최소 기록은 유지하므로 동일 ID 재등록도 410입니다. 세션 만료 후에는 같은 관리자 계정으로 로그인해 조회할 수 있습니다. 프론트는 요청 ID와 원래 본문 전체를 보관해 새로고침 후 조회·재전송에 사용하며 오류를 미처리로 바꾸거나 새 ID로 반복 접수하지 않습니다. 중단된 PROCESSING 중 2분이 지난 비활성 요청은 1분 간격 정리에서 FAILED/ARTIST_REGISTRATION_INTERRUPTED로 전환하고 실행 중 잠긴 요청은 건너뜁니다. FAILED는 생성 트랜잭션이 반영되지 않은 상태이며 오류를 수정한 후 새 ID로 별도 요청할 수 있습니다. 저장소 장애는 503이며 결과를 단정할 수 없습니다.")
  public ResponseEntity<ArtistRegistrationDtos.Result> artistRegistration(
      @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String requestId,
      @AuthenticationPrincipal SessionUser user) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(registrations.get(user.id(), requestId));
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
