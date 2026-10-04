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
  @Operation(summary = "24시간 조회수 급상승 목록")
  public ResponseEntity<Trending> trending(@RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.trending(size));
  }
}
