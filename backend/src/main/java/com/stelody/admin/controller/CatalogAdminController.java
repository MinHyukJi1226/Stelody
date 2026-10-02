package com.stelody.admin.controller;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.service.CatalogManagementService;
import com.stelody.auth.domain.SessionUser;
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

  @GetMapping({"/members", "/artists", "/works", "/songs", "/channels"})
  public ResponseEntity<Page> list(
      jakarta.servlet.http.HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String q) {
    String path = request.getRequestURI();
    return response(service.list(path.substring(path.lastIndexOf('/') + 1), page, size, q), false);
  }

  @GetMapping({"/members/{id}", "/artists/{id}", "/works/{id}", "/songs/{id}", "/channels/{id}"})
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
    "/channels/{id}/audit"
  })
  public ResponseEntity<AuditPage> audit(
      jakarta.servlet.http.HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    String[] path = request.getRequestURI().split("/");
    return response(service.audits(path[path.length - 3], id, page, size), false);
  }

  @PostMapping("/members")
  public ResponseEntity<Saved<Member>> createMember(
      @Valid @RequestBody MemberInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.member(null, body, user.id()), true);
  }

  @PutMapping("/members/{id}")
  public ResponseEntity<Saved<Member>> updateMember(
      @PathVariable UUID id,
      @Valid @RequestBody MemberInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.member(id, body, user.id()), false);
  }

  @PostMapping("/artists")
  public ResponseEntity<Saved<Artist>> createArtist(
      @Valid @RequestBody ArtistInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.artist(null, body, user.id()), true);
  }

  @PutMapping("/artists/{id}")
  public ResponseEntity<Saved<Artist>> updateArtist(
      @PathVariable UUID id,
      @Valid @RequestBody ArtistInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.artist(id, body, user.id()), false);
  }

  @PostMapping("/works")
  public ResponseEntity<Saved<Work>> createWork(
      @Valid @RequestBody WorkInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.work(null, body, user.id()), true);
  }

  @PutMapping("/works/{id}")
  public ResponseEntity<Saved<Work>> updateWork(
      @PathVariable UUID id,
      @Valid @RequestBody WorkInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.work(id, body, user.id()), false);
  }

  @PostMapping("/channels")
  public ResponseEntity<Channel> createChannel(
      @Valid @RequestBody ChannelInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.channel(null, body, user.id()), true);
  }

  @PutMapping("/channels/{id}")
  public ResponseEntity<Channel> updateChannel(
      @PathVariable UUID id,
      @Valid @RequestBody ChannelInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.channel(id, body, user.id()), false);
  }

  @PostMapping("/songs")
  public ResponseEntity<Saved<Song>> createSong(
      @Valid @RequestBody SongInput body, @AuthenticationPrincipal SessionUser user) {
    return response(service.song(null, body, user.id()), true);
  }

  @PutMapping("/songs/{id}")
  public ResponseEntity<Saved<Song>> updateSong(
      @PathVariable UUID id,
      @Valid @RequestBody SongInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.song(id, body, user.id()), false);
  }

  @PostMapping("/reviews/{id}/registration")
  public ResponseEntity<Video> register(
      @PathVariable UUID id,
      @Valid @RequestBody Registration body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.register(id, body, user.id()), true);
  }

  @PostMapping("/songs/{id}/videos")
  public ResponseEntity<Video> registerUrl(
      @PathVariable UUID id,
      @Valid @RequestBody VideoRegistration body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.registerUrl(id, body, user.id()), true);
  }

  @PutMapping("/songs/{id}/videos/{videoId}")
  public ResponseEntity<Video> video(
      @PathVariable UUID id,
      @PathVariable UUID videoId,
      @RequestParam long songVersion,
      @Valid @RequestBody VideoInput body,
      @AuthenticationPrincipal SessionUser user) {
    return response(service.video(id, videoId, songVersion, body, user.id()), false);
  }
}
