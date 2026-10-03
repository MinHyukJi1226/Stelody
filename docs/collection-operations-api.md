# 관리자 수집 운영 API

등록 영상 갱신과 신규·과거 업로드 탐색의 상태, 진행, 실패 및 수동 재시도 요청을 관리한다. 현재 DB의 ADMIN 역할을 확인한 세션만 접근할 수 있다. 변경 요청에는 CSRF 토큰이 필요하며 응답은 `Cache-Control: no-store`다.

## 엔드포인트

| 경로 | 용도 |
|---|---|
| GET `/api/v1/admin/collection-status` | 최근 실행·성공·실패 시각, 지연 경고, 검토 대기 수, 활성 재시도 |
| GET `/api/v1/admin/collection-runs` | 실행 이력 목록 |
| GET `/api/v1/admin/collection-runs/{kind}/{id}` | 실행 상세와 진행 수 |
| POST `/api/v1/admin/collection-runs/{kind}/{id}/retries` | 실패 실행의 재시도 접수 |
| GET `/api/v1/admin/collection-retries/{id}` | 접수·실행·결과 조회 |

`kind`는 `VIDEO` 또는 `DISCOVERY`다. 목록의 기본값은 `kind=VIDEO&page=0&size=20`이며, `page`는 0~10000, `size`는 1~50이다. 선택 `status`는 `RUNNING`, `SUCCEEDED`, `FAILED`, `QUOTA_EXHAUSTED`, `TIMED_OUT`이다. 최근 시작 시각·ID 내림차순이며 `{items,page,size,hasNext}`를 반환한다. 탐색 실행은 `mode=NEW|BACKFILL`, 과거 탐색은 `channelId`도 제공한다.

VIDEO의 진행은 `totalCount`, `observedCount`, `skippedCount`, `pendingCount`다. DISCOVERY는 완료한 `pages`, 저장한 `candidates`를 제공하며 전체 대상 수를 추정하지 않는다. `attempt`는 VIDEO의 현재 시도 번호이며 DISCOVERY는 새 실행을 만들므로 항상 1이다. 원문 API 오류·응답·자격 증명을 반환하지 않는다.

## 운영 상태와 지연 경고

`collection-status`는 `checkedAt`, `retryEnabled`, `video`, `discovery`, `pendingReviews`, `deferredReviews`, `activeRetry`를 반환한다. `deferredReviews`는 검토 대기 중 보류 또는 마지막 원본 관측 후 30일이 지난 항목 수이며 `pendingReviews`에 포함된다.

각 수집 상태에는 `latest`, `lastSuccessAt`, `lastFailureAt`, `missedSlots`, `delayed`, `runningOverdue`가 있다. VIDEO는 매시간 UTC `:17`, 전체 채널 NEW 탐색은 짝수 UTC 시간 `:17`의 예정 슬롯을 기준으로 최근 성공 이후 미완료 슬롯을 계산한다. 3슬롯 이상이면 `delayed=true`다. 오래된 슬롯을 늦게 재개해 성공해도 새 슬롯 수집이 완료된 것으로 계산하지 않는다. BACKFILL이나 특정 채널의 수동 탐색 성공은 전체 NEW 탐색 지연을 해소하지 않는다. 탐색의 `lastSuccessAt`도 전체 채널 NEW 기준이며 최근 실행·실패는 모든 탐색 모드를 포함한다. 11분을 넘긴 RUNNING 실행은 `runningOverdue=true`이며 조회만으로 실행 상태를 변경하지 않는다.

성공 이력이 없으면 최초 실행부터 계산한다. 실행 자체가 없으면 시각·최근 실행은 null, 미완료 슬롯은 0이다. 수집 활성화 여부나 예약 실행 전송 성공을 이 값만으로 판단할 수 없다. `delayed`는 API 응답의 관리자 경고 값이며 별도 알림 전송은 제공하지 않는다. GitHub 예약 실행은 지연될 수 있다.

## 재시도 접수

```json
{
  "requestId": "6b7a60e6-7f03-4e37-85ef-142a7ae43d83",
  "attempt": 2,
  "reason": "일시적인 API 오류 해소 후 다시 수집"
}
```

`requestId`는 클라이언트가 생성한 UUID, `attempt`는 상세 조회에서 받은 현재 시도 번호다. 사유는 공백을 제외한 1~500자다. 접수 시 관리자 ID·대상·사유를 감사 이력에 함께 저장한다. 이력 저장에 실패하면 접수도 롤백한다.

최근 48시간 안의 `FAILED`, `QUOTA_EXHAUSTED`, `TIMED_OUT` 실행만 접수한다. VIDEO는 `logicalSlot`, DISCOVERY는 최초 실행의 `startedAt` 기준이다. RUNNING·성공 실행과 오래된 실행은 거절한다. 같은 requestId·종류·대상·시도 번호의 요청은 기존 결과를 반환하며, 다른 의미로 같은 ID를 재사용하면 충돌한다. 동일 대상이라도 완료 후 다시 시도하려면 최신 시도 번호와 새 requestId를 사용한다.

접수는 HTTP **202**와 `Location: /api/v1/admin/collection-retries/{requestId}`를 반환한다. 접수 응답에는 `id`, `kind`, `runId`, `expectedAttempt`, `status`, `createdAt`, `startedAt`, `finishedAt`, `executionRunId`, `errorCode`가 있다. 상태는 `QUEUED → RUNNING → SUCCEEDED|FAILED`다. `executionRunId`가 생기면 실행 상세 API로 실제 진행을 조회한다. 완료한 동일 요청을 다시 보내도 202로 기존 결과를 반환한다.

전체 수집에 활성 요청을 최대 1개로 제한한다. 동시 요청은 DB 제약으로도 제한한다. 재시도 대기 중 정상 수집이 먼저 끝난 경우 추가 API 호출 없이 성공 결과를 확정한다. 대기 중 시도 번호가 바뀌면 이전 요청을 실패 처리하고, 관리자는 최신 상태로 다시 요청한다.

## 수집기로 전달하는 방식

웹은 DB에 요청을 저장한다. 수집 전용 프로세스는 **다음 정기·수동 실행에서** 요청을 하나 처리하고 종료한다. 웹 요청이 GitHub Actions를 즉시 실행하거나 YouTube API를 직접 호출하지 않는다. 예약 실행만 이용하면 보통 다음 매시간 작업까지 기다리며 GitHub 지연에 따라 더 늦을 수 있다. 빠른 확인은 기존 workflow의 수동 실행 또는 로컬 `--collector` 실행을 이용한다. 활성 요청이 처리된 호출에서는 일반 수집을 추가 실행하지 않는다.

- VIDEO는 지정한 기존 실행의 미처리 영상만 재개한다. 완료 표본과 슬롯, 실행 ID를 유지하고 시도 번호를 증가시킨다.
- DISCOVERY는 기존 체크포인트를 사용해 새 실행을 만든다. NEW는 채널당 최대 3페이지, BACKFILL은 기존 채널과 과거 커서로 최대 1페이지를 처리한다. 탐색 성공은 이번 제한된 배치의 성공이며 모든 과거 영상 탐색 완료를 의미하지 않는다.
- 기존 전체 수집 잠금, 소유 토큰, 최대 10분 예산을 공유한다. 잠금을 얻지 못하면 요청을 대기 상태로 유지한다.
- 프로세스 중단으로 RUNNING 요청이 남으면 다음 수집기 호출에서 재개한다. 실행이 이미 성공했다면 같은 작업을 다시 수행하지 않고 결과만 복구한다. 요청별 실행 토큰으로 이전 프로세스가 새 처리 결과를 덮어쓰지 못하게 한다.
- 신규 탐색 재시도도 `DISCOVERY_ENABLED=true`가 필요하다. 비활성이면 `DISCOVERY_DISABLED`로 실패한다. 자동 분류 허용 설정은 기존 `DISCOVERY_CLASSIFICATION_ALLOWED`를 따른다.
- 완료 요청은 종료 후 30일이 지나면 활성 수집기 실행 시 정리한다. 관리자 사유·이력은 수집기에서 읽지 못한다. 원본 수집 이력의 기존 보관 규칙은 유지한다.

## 설정

1. 웹 마이그레이션 계정으로 **V15**를 적용한다. 기존 V1~V14와 수집 권한 스크립트는 변경하지 않는다.
2. 스키마 소유자로 [운영 요청 수집 권한](../infra/sql/collection-operations-grants.sql)을 수집 전용 역할에 적용한다. 기존 [등록 영상 권한](../infra/sql/collector-grants.sql), [탐색 권한](../infra/sql/discovery-grants.sql), [통계 권한](../infra/sql/statistics-collector-grants.sql)이 필요한 구성에서는 함께 적용되어 있어야 한다.
3. 웹 실행 환경과 수집 실행 환경에 `COLLECTION_RETRY_ENABLED=true`를 설정한다. 기본은 false다. GitHub Actions 사용 시 같은 이름의 저장소 **Variable**을 설정한다. 수집 기본 자격 증명과 `COLLECTOR_ENABLED`도 필요하다.
4. 탐색 재시도까지 사용할 경우 수집 환경의 `DISCOVERY_ENABLED=true`를 설정한다. 웹·수집기는 동일 DB를 사용한다.

웹 역할은 실행 기록·진행과 요청 결과 조회, 요청의 ID·종류·대상·기대 시도 번호 INSERT만 허용한다. 웹 역할에 결과·소유 토큰 UPDATE 권한을 부여하지 않는다. 수집 역할은 요청 조회·갱신·정리만 허용하며 관리자 사유가 담긴 `catalog_audit`, 회원, 세션, 개인 목록, OAuth 토큰 테이블 접근을 추가하지 않는다. advisory lock을 유지할 수 있는 직접 연결 또는 session pooler가 필요하다.

`COLLECTION_RETRY_ENABLED`를 웹에서만 켜고 수집기에서 끄면 요청이 대기한다. 활성화 전 마이그레이션·권한과 두 실행 환경을 함께 준비한다. 수집기를 비활성화한 동안에는 예약 처리·보관 정리가 진행되지 않는다.

## 오류와 확인

입력은 400 `INVALID_COLLECTION_REQUEST`, 없는 실행·요청은 404 `COLLECTION_RUN_NOT_FOUND|COLLECTION_RETRY_NOT_FOUND`다. 재시도 기능 비활성은 503 `COLLECTION_RETRY_DISABLED`다. 접수의 409 코드는 `COLLECTION_RUN_CHANGED`, `COLLECTION_RUN_NOT_RETRYABLE`, `COLLECTION_RETRY_EXPIRED`, `COLLECTION_RETRY_KEY_REUSED`, `COLLECTION_RETRY_ALREADY_ACTIVE`다.

처리 결과는 기존 정규화된 수집 오류 코드와 `RETRY_RUN_MISSING`, `RETRY_RUN_CHANGED`, `RETRY_EXPIRED`, `DISCOVERY_DISABLED` 등을 제공한다. 연결·잠금 처리 중단은 로그에 `RETRY_PROCESSING_FAILED`로 남고, 미완료 요청은 복구 가능하게 유지한다. HTTP 202를 수집 성공으로 처리하지 않는다.

```sh
# backend 디렉토리, Java 21과 Docker 필요
./gradlew integrationTest --tests '*CollectionOperations*Test' --tests '*VideoCollectorIntegrationTest' --tests '*DiscoveryCollectorIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

Testcontainers의 분리된 웹·마이그레이션·수집 계정과 모의 YouTube API로 검증한다. 실제 운영 DB 권한 적용, 저장소 Variable 활성화, 예약 실행과 관리자 프론트 화면은 별도 적용 사항이다.

### 2026-10-03 로컬 검증

- `spotlessApply check bootJar --no-daemon` 성공: 단위 테스트 90개, PostgreSQL 통합 테스트 265개, 총 355개 실패·건너뜀 없이 통과했다.
- 신규 운영 테스트 23개로 ADMIN·CSRF·역할 철회, 목록·처리 수·검토 대기 수, UTC `:17` 경계의 3슬롯 경고, 특정 채널·BACKFILL 성공의 지연 판정 분리, 접수 멱등성·감사 롤백, 동시 접수·수집기 중복 방지, 미처리 영상 재개, 탐색 커서 유지, 중단·완료 복구, 만료·할당량·비활성 실패와 V14→V15 업그레이드·권한 제한을 확인했다.
- 비웹 launcher의 활성화 변수 전달과 DB 요청 결과 확정을 확인했다. 실제 외부 API는 호출하지 않았으며 운영 DB·저장소 Variable을 변경하지 않았다.
- Backend CI와 수집 workflow는 actionlint 1.7.7 검사에 통과했다. ShellCheck가 없어 해당 검사는 제외했다.

V16 이후 탐색 실행 응답에는 `ruleVersion`을 추가한다. 해당 실행에 고정된 분류 규칙 버전이며 기존 실행과 등록 영상 수집 실행은 null이다. 정책 비허용 탐색은 `title-v1`, 정책 허용 탐색은 `title-v2:<관리자 규칙 버전>`을 기록한다. [규칙 편집 계약](classification-management-api.md)을 참고한다.
