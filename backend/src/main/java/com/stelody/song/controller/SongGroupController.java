package com.stelody.song.controller;

import com.stelody.song.dto.SongDtos;
import com.stelody.song.service.SongGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SongGroupController {
  private final SongGroupService service;

  public SongGroupController(SongGroupService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/songs/groups")
  @Operation(
      summary = "전체곡 분류 그룹과 첫 곡 페이지",
      description =
          "TYPE·MEMBER·ORIGINAL_ARTIST 중 하나로 분류합니다. 그룹 size 기본 10(1~10), 그룹별 songSize 기본 6(1~20)이며 그룹·곡 커서는 독립적입니다. ORIGINAL_ARTIST는 오리지널곡을 ORIGINAL(오리지널) 그룹으로 맨 위에 제공합니다. 이후 가수별 그룹과 OTHERS(기타)에는 커버곡만 포함합니다. 원곡 가수는 공개 고유 커버곡이 2곡 이상일 때 개별 그룹을 만들고 1곡인 가수와 원곡 가수 미등록 커버곡은 기타에 포함합니다. 여러 멤버·원곡 가수에 연결된 곡은 해당 각 그룹에 포함하며 그룹 안에서는 곡 ID로 중복을 제거합니다. 오리지널→커버, 기존 멤버 표시 순서, 정규화된 원곡 가수 이름(C 정렬)·ID순이며 기타는 마지막입니다. 빈 그룹은 제외합니다. 공개 목록과 같은 대표 영상·노출 조건을 사용합니다.")
  public ResponseEntity<SongGroupService.Page> list(
      @Parameter(schema = @Schema(allowableValues = {"TYPE", "MEMBER", "ORIGINAL_ARTIST"}))
          @RequestParam
          String groupBy,
      @RequestParam(defaultValue = "LATEST") String sort,
      @RequestParam(defaultValue = "10") int size,
      @Parameter(schema = @Schema(minimum = "1", maximum = "20")) @RequestParam(defaultValue = "6")
          int songSize,
      @RequestParam(required = false) String cursor) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.list(groupBy, sort, size, songSize, cursor));
  }

  @GetMapping("/api/v1/songs/groups/{groupId}/songs")
  @Operation(
      summary = "분류 그룹 내부 곡 커서 조회",
      description =
          "groupId는 그룹 목록이 반환한 ID를 사용합니다. TYPE은 ORIGINAL/COVER, MEMBER와 원곡 가수는 UUID, ORIGINAL_ARTIST의 오리지널 그룹은 ORIGINAL, 기타는 OTHERS입니다. ORIGINAL_ARTIST의 이전 버전 커서는 400 INVALID_CATALOG_QUERY이므로 첫 페이지부터 재조회합니다. size는 1~50(기본 20)입니다. 분류·정렬·그룹이 다른 커서 및 그룹 목록 커서는 400 INVALID_CATALOG_QUERY입니다. 인기순 조회수 집계가 교체되면 409 VIEW_PUBLICATION_CHANGED로 첫 페이지부터 다시 조회합니다. 유효한 ID여도 공개 곡이 없거나 커버곡 2곡 미만인 개별 가수 그룹이면 404 CATALOG_NOT_FOUND입니다.")
  public ResponseEntity<SongDtos.Page> songs(
      @PathVariable String groupId,
      @Parameter(schema = @Schema(allowableValues = {"TYPE", "MEMBER", "ORIGINAL_ARTIST"}))
          @RequestParam
          String groupBy,
      @RequestParam(defaultValue = "LATEST") String sort,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String cursor) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.listSongs(groupBy, groupId, sort, size, cursor));
  }
}
