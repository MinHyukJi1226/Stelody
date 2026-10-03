package com.stelody.song.controller;

import com.stelody.statistics.dto.StatisticsDtos.*;
import com.stelody.statistics.service.ViewStatisticsService;
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
  public ResponseEntity<Views> views(
      @PathVariable UUID id, @RequestParam(defaultValue = "7") int days) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.views(id, days));
  }
}
