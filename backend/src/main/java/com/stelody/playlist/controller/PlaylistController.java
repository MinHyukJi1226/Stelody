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
  @Operation(
      summary = "플레이리스트 곡 목록",
      description =
          "position 오름차순으로 이용 불가 항목까지 조회합니다. 전체 순서 편집은 첫 페이지의 version을 보관하고 nextCursor를 따라 hasNext=false까지 모든 페이지를 모은 뒤 진행합니다. 페이지마다 같은 version이어야 하며 size는 변경할 수 있습니다. 조회 중 이름·항목·순서가 변경되면 기존 커서 요청은 409 PLAYLIST_CHANGED입니다. 첫 페이지부터 다시 조회하고 편집 내용을 확인합니다. 카탈로그 공개 상태 변경은 목록 version을 바꾸지 않으므로 available과 song 정보는 페이지마다 달라질 수 있습니다. 잘못된 커서·다른 계정 또는 목록의 커서는 400 INVALID_PLAYLIST_REQUEST입니다.")
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
  @Operation(
      summary = "플레이리스트 곡 순서 변경",
      description =
          "전체 페이지를 조회한 version과 원하는 순서의 전체 itemIds를 전달합니다. songId가 아닌 목록 항목 id이며 이용 불가 항목도 빠짐없이 한 번씩 포함합니다. 일부 항목만 보내거나 중복·다른 목록 항목을 포함하면 400 INVALID_PLAYLIST_REQUEST이고 순서와 version은 유지됩니다. 빈 목록은 itemIds=[]입니다. 편집 중 이름·항목·순서가 변경되면 409 PLAYLIST_CHANGED이며 전체 페이지를 다시 조회해 편집 내용을 확인해야 합니다. version만 갱신해 이전 순서를 재전송하지 않습니다. 성공하면 순서 전체를 한 번에 저장하고 version을 1 증가시킨 Summary를 반환합니다. 동일한 순서·빈 목록 저장도 version을 증가시킵니다.")
  public PlaylistDtos.Summary reorder(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @Valid @RequestBody PlaylistDtos.Order body) {
    return items.reorder(user.id(), id, body.version(), body.itemIds());
  }
}
