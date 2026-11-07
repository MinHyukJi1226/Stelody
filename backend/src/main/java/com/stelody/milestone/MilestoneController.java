package com.stelody.milestone;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MilestoneController {
  private final MilestoneService service;

  public MilestoneController(MilestoneService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/songs/milestones")
  @Operation(
      summary = "조회수 임박·달성 소식",
      description =
          "10만 회부터 10만 회 간격의 목표입니다. approaching은 다음 목표까지 5000회 이하로 남은 공개 대표 영상이며 남은 횟수·곡 ID 순입니다. achievements는 감지 후 24시간 이내 달성 소식입니다. 곡별 가장 최근 달성 한 건을 남기고, 감지 시각이 같으면 가장 높은 목표를 선택합니다. 곡별로 합친 뒤 감지 시각·목표 내림차순으로 최신 10곡을 제공합니다. 최초 관측은 기준점만 만들고 과거 달성은 생성하지 않습니다. 조회수 감소 후 같은 목표를 다시 넘겨도 중복 생성하지 않습니다. 30일간 관측이 없으면 기준점을 폐기하고 다음 관측을 새 기준점으로 사용합니다. 비공개·이용 불가·숨김·대표 영상 교체·수집 제외 채널은 소식에서 제외합니다. 최대 2시간 이내 실제 관측만 제공합니다. DISABLED는 기능 비활성, PENDING은 유효한 관측 없음, READY는 조회 가능 상태이며 두 배열이 비어 있을 수 있습니다. detectedAt은 실제 달성 시각이 아닌 서버 확인 시각입니다. 예상 도달 시각은 내부 조회 주기 조절에만 사용하며 사용자에게 확정 시각으로 제공하지 않습니다. 추가 조회는 임박 영상만 1·10·60분으로 조절하며 API 장애·한도·서버 휴면·유튜브 집계 지연에 따라 늦어질 수 있습니다. 읽기 전용이며 이 GET 요청으로 외부 조회·수집을 실행하지 않습니다. 기존 곡 카드의 viewCount는 정각 수집 기준이고 approaching.viewCount·observedAt은 별도의 최신 확인값입니다.")
  public ResponseEntity<MilestoneDtos.Feed> feed() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.feed());
  }
}
