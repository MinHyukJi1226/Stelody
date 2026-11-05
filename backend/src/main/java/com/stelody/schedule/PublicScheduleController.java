package com.stelody.schedule;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicScheduleController {
  @Schema(requiredProperties = {"items"})
  public record Schedule(List<PublicScheduleRepository.Item> items) {}

  private final PublicScheduleRepository repository;

  public PublicScheduleController(PublicScheduleRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/api/v1/songs/upcoming")
  @Operation(
      summary = "YouTube 공개 예정 영상",
      description =
          "수집이 활성화된 멤버·그룹 채널에서 YouTube API가 공개 예약으로 확인한 영상만 제공합니다. 제목과 시작 시각은 YouTube 원본이며 시작 시각은 UTC입니다. 일반 예약 방송과 최초공개를 구분하는 필드는 제공되지 않으므로 화면에는 ‘YouTube 공개 예정’으로 표시하고 원본 링크와 출처를 함께 제공합니다. 시작 시각이 지난 항목과 2시간 넘게 갱신되지 않은 항목은 제외합니다. 예약 시작 시각·영상 ID 오름차순 최대 50건이며 대상이 없으면 items는 빈 배열입니다. 요청 시 외부 API를 호출하지 않습니다.")
  public ResponseEntity<Schedule> upcoming() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new Schedule(repository.upcoming()));
  }
}
