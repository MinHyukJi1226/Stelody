package com.stelody.playlist.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.playlist.dto.PlaylistDtos;
import com.stelody.playlist.service.PlaylistItemService;
import com.stelody.playlist.service.PlaylistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaylistController {
  private final PlaylistService playlists;
  private final PlaylistItemService items;

  public PlaylistController(PlaylistService playlists, PlaylistItemService items) {
    this.playlists = playlists;
    this.items = items;
  }

  @PostMapping("/api/v1/me/playlists")
  @Operation(summary = "개인 플레이리스트 생성")
  public ResponseEntity<PlaylistDtos.Summary> create(
      @AuthenticationPrincipal SessionUser user, @Valid @RequestBody PlaylistDtos.Create body) {
    var playlist = playlists.create(user.id(), body.name());
    return ResponseEntity.created(URI.create("/api/v1/me/playlists/" + playlist.id()))
        .body(playlist);
  }

  @GetMapping("/api/v1/me/playlists")
  @Operation(
      summary = "개인 플레이리스트 목록",
      description =
          "songId를 지정하면 각 목록의 containsSong으로 저장 여부를 확인합니다. "
              + "목록을 필터링하지 않으며 기존 정렬·커서·전체 목록 개수를 유지합니다. "
              + "조회 이후 목록이 변경될 수 있으므로 곡 추가 API의 version·중복 검증을 계속 사용합니다.")
  public PlaylistDtos.Page list(
      @AuthenticationPrincipal SessionUser user,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String cursor,
      @Parameter(
              description =
                  "포함 여부를 확인할 곡 UUID. 생략하면 containsSong은 null입니다. "
                      + "이용 불가 곡도 저장되어 있으면 true이며, 저장되지 않은 곡이나 존재하지 않는 UUID는 false입니다.")
          @RequestParam(required = false)
          UUID songId) {
    return playlists.list(user.id(), size, cursor, songId);
  }

  @GetMapping("/api/v1/me/playlists/{id}")
  @Operation(summary = "개인 플레이리스트 상세")
  public PlaylistDtos.Summary detail(
      @AuthenticationPrincipal SessionUser user, @PathVariable UUID id) {
    return playlists.detail(user.id(), id);
  }

  @PatchMapping("/api/v1/me/playlists/{id}")
  @Operation(summary = "플레이리스트 이름 변경")
  public PlaylistDtos.Summary rename(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @Valid @RequestBody PlaylistDtos.Rename body) {
    return playlists.rename(user.id(), id, body.version(), body.name());
  }

  @DeleteMapping("/api/v1/me/playlists/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "플레이리스트 삭제")
  public void delete(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @RequestParam long version) {
    playlists.delete(user.id(), id, version);
  }

  @GetMapping("/api/v1/me/playlists/{id}/items")
  @Operation(summary = "플레이리스트 곡 목록")
  public PlaylistDtos.Items items(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String cursor) {
    return items.list(user.id(), id, size, cursor);
  }

  @PostMapping("/api/v1/me/playlists/{id}/items")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "플레이리스트에 곡 추가")
  public PlaylistDtos.Added add(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @Valid @RequestBody PlaylistDtos.Add body) {
    return items.add(user.id(), id, body.version(), body.songId());
  }

  @DeleteMapping("/api/v1/me/playlists/{id}/items/{itemId}")
  @Operation(summary = "플레이리스트에서 곡 제거")
  public PlaylistDtos.Summary remove(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @PathVariable UUID itemId,
      @RequestParam long version) {
    return items.remove(user.id(), id, version, itemId);
  }

  @PutMapping("/api/v1/me/playlists/{id}/order")
  @Operation(summary = "플레이리스트 곡 순서 변경")
  public PlaylistDtos.Summary reorder(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @Valid @RequestBody PlaylistDtos.Order body) {
    return items.reorder(user.id(), id, body.version(), body.itemIds());
  }
}
