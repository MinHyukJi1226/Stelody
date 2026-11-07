package com.stelody.song.controller;

import com.stelody.statistics.dto.StatisticsDtos.*;
import com.stelody.statistics.service.ViewStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ViewStatisticsController {
  private final ViewStatisticsService service;

  public ViewStatisticsController(ViewStatisticsService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/songs/{id}/views")
  @Operation(summary = "대표 영상의 일별 조회수 추이")
  public ResponseEntity<Views> views(
      @PathVariable UUID id, @RequestParam(defaultValue = "7") int days) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.views(id, days));
  }

  @GetMapping("/api/v1/songs/trending")
  @Operation(
      summary = "조회수 급상승 목록",
      description =
          "현재 집계 조회수에서 집계 시각 24시간 전까지의 마지막 관측 조회수를 뺀 증가량 순입니다. "
              + "수집 누락 시 30일 보관 범위의 이전 기록을 사용하므로 실제 비교 기간은 24시간보다 길 수 있습니다. "
              + "실제 비교 시각은 startObservedAt/endObservedAt으로 반환합니다. 공개 24시간 미만인 영상은 "
              + "24시간 전 비교 기록이 없으면 공개 후 첫 관측과 최신 관측의 증가량을 사용합니다.")
  public ResponseEntity<Trending> trending(@RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.trending(size));
  }
}
