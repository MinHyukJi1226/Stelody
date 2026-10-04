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
  @Operation(summary = "즐겨찾기 목록")
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
  @Operation(summary = "즐겨찾기 저장")
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
