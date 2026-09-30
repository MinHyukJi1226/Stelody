package com.stelody.playlist.controller;

import com.stelody.auth.domain.SessionUser;
import com.stelody.playlist.dto.PlaylistDtos;
import com.stelody.playlist.service.PlaylistItemService;
import com.stelody.playlist.service.PlaylistService;
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
  public ResponseEntity<PlaylistDtos.Summary> create(
      @AuthenticationPrincipal SessionUser user, @Valid @RequestBody PlaylistDtos.Create body) {
    var playlist = playlists.create(user.id(), body.name());
    return ResponseEntity.created(URI.create("/api/v1/me/playlists/" + playlist.id()))
        .body(playlist);
  }

  @GetMapping("/api/v1/me/playlists")
  public PlaylistDtos.Page list(
      @AuthenticationPrincipal SessionUser user,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String cursor) {
    return playlists.list(user.id(), size, cursor);
  }

  @GetMapping("/api/v1/me/playlists/{id}")
  public PlaylistDtos.Summary detail(
      @AuthenticationPrincipal SessionUser user, @PathVariable UUID id) {
    return playlists.detail(user.id(), id);
  }

  @PatchMapping("/api/v1/me/playlists/{id}")
  public PlaylistDtos.Summary rename(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @Valid @RequestBody PlaylistDtos.Rename body) {
    return playlists.rename(user.id(), id, body.version(), body.name());
  }

  @DeleteMapping("/api/v1/me/playlists/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @RequestParam long version) {
    playlists.delete(user.id(), id, version);
  }

  @GetMapping("/api/v1/me/playlists/{id}/items")
  public PlaylistDtos.Items items(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String cursor) {
    return items.list(user.id(), id, size, cursor);
  }

  @PostMapping("/api/v1/me/playlists/{id}/items")
  @ResponseStatus(HttpStatus.CREATED)
  public PlaylistDtos.Added add(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @Valid @RequestBody PlaylistDtos.Add body) {
    return items.add(user.id(), id, body.version(), body.songId());
  }

  @DeleteMapping("/api/v1/me/playlists/{id}/items/{itemId}")
  public PlaylistDtos.Summary remove(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable UUID id,
      @PathVariable UUID itemId,
      @RequestParam long version) {
    return items.remove(user.id(), id, version, itemId);
  }
}
