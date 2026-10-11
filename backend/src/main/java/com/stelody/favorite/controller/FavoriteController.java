package com.stelody.favorite.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.favorite.dto.FavoriteDtos;
import com.stelody.favorite.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FavoriteController {
  private final FavoriteService favorites;

  public FavoriteController(FavoriteService favorites) {
    this.favorites = favorites;
  }

  @GetMapping("/api/v1/me/favorites")
  @Operation(
      summary = "즐겨찾기 목록",
      description =
          "최근 저장순만 지원하며 sort 파라미터는 제공하지 않습니다. savedAt 내림차순, 같은 시각이면 songId 내림차순입니다. 곡 공개일·조회수는 정렬에 사용하지 않습니다. 이용 불가 항목도 같은 순서로 포함합니다. 각 페이지는 요청 당시 DB 스냅샷으로 조회하며 전체 페이지를 고정하지 않습니다. 조회 도중 새로 저장하거나 해제 후 다시 저장한 항목은 첫 페이지를 새로 조회해 확인합니다. 다음 페이지는 직전 nextCursor를 그대로 사용하며 size는 변경할 수 있습니다. 잘못된 커서 또는 다른 계정의 커서는 400 INVALID_FAVORITE_QUERY입니다.")
  public FavoriteDtos.Page list(
      @AuthenticationPrincipal SessionUser user,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String cursor) {
    return favorites.list(user.id(), size, cursor);
  }

  @GetMapping("/api/v1/me/favorites/{songId}")
  @Operation(summary = "곡의 즐겨찾기 여부")
  public FavoriteDtos.State state(
      @AuthenticationPrincipal SessionUser user, @PathVariable UUID songId) {
    return favorites.state(user.id(), songId);
  }

  @PutMapping("/api/v1/me/favorites/{songId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "즐겨찾기 저장",
      description = "이미 저장된 곡에 대한 요청은 savedAt과 목록 순서를 유지합니다. 해제 후 다시 저장하면 새 저장 시각으로 정렬합니다.")
  public void save(@AuthenticationPrincipal SessionUser user, @PathVariable UUID songId) {
    favorites.save(user.id(), songId);
  }

  @DeleteMapping("/api/v1/me/favorites/{songId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "즐겨찾기 해제")
  public void delete(@AuthenticationPrincipal SessionUser user, @PathVariable UUID songId) {
    favorites.delete(user.id(), songId);
  }
}
