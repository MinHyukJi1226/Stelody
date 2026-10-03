# 조회수 추이·급상승 API

## 범위와 데이터

대표 영상 1개의 실제 관측값만 사용한다. 영상 간 조회수를 합산하거나 대표 변경 전후 기록을 이어 붙이지 않는다. 로그인 없이 읽을 수 있지만 기존 공개 곡 조건(PUBLISHED·대표 영상 PUBLIC·확인된 멤버·공개일)을 만족해야 한다. 숨김·비공개·대표 없음·미확인 참여·없는 곡은 404다. 응답은 `Cache-Control: no-store`다.

## 곡의 조회수 추이

GET `/api/v1/songs/{id}/views?days=7`

days는 7 또는 30이며 기본은 7이다. `songId/videoId/youtubeId`, `timeZone=Asia/Seoul`, `days/startDate/endDate`, `collectionStartedAt`, `latestViewCount/latestObservedAt`, `publicationId/publishedAt`, `points`를 반환한다. 시간은 UTC ISO 8601, 날짜는 KST ISO 날짜다.

- 오늘을 포함한 KST 7일 또는 30일의 날짜를 오름차순으로 빠짐없이 반환한다. 오늘의 값은 현재까지의 마지막 성공 관측값이다.
- points는 `{date,viewCount,observedAt,source}`다. 실제 0은 숫자 0, 미관측 날짜는 나머지 세 필드가 null이다. 보간·전날 값 복사·현재 값으로 과거 채우기를 하지 않는다. 관측 감소도 원본대로 반환한다.
- 출처는 저장된 관측의 `YOUTUBE_DATA_API_V3`다. 프론트는 null 구간의 선을 끊고 날짜별 표와 출처·관측 시각을 표시해야 한다. 한 점만 있으면 수집 중 상태를 표시한다.
- collectionStartedAt은 해당 영상에서 조회수가 처음 관측된 수집 시각이다. 조회수 필드가 없는 응답은 시작 시각을 만들지 않는다. 숫자 표본과 별개인 자체 수집 시작 시각만 보존한다. V13 이전 자료는 당시 남은 표본 중 가장 이른 시각으로 보완하며, 이미 삭제된 과거 시작 시각을 추정하지 않는다.
- latestViewCount/latestObservedAt은 목록·상세와 같은 완료된 수집 게시본의 값이다. 게시 전 성공한 배치의 일별 관측은 points에 먼저 반영될 수 있다. 수집 부분 실패가 공개 최신값을 교체하지 않는다.
- 수집 정리가 지연되어도 30일을 넘거나 미래 시각인 관측을 응답하지 않는다. 공개 최신값도 만료되면 null이다. 수집 이전에는 날짜별 null 목록을 반환한다.
- 하나의 REPEATABLE_READ 트랜잭션에서 공개 조건·게시본·대표 영상·일별 기록을 조회한다.

## 최근 24시간 급상승

GET `/api/v1/songs/trending?size=20`

size는 1~50이며 기본 20이다. 메인용 상위 목록으로 제공하고 커서 페이지는 제공하지 않는다. `status/metricSource/periodHours/toleranceMinutes/publicationId/referenceAt/items`를 반환한다. `metricSource=STELODY_VIEW_GROWTH`이며 YouTube 공식 순위로 표시하면 안 된다.

| status | 의미 |
| --- | --- |
| DISABLED | 운영 활성화 또는 정책 확인 설정이 꺼짐. 숫자·기준 시각·게시본 없이 빈 items |
| PENDING | 성공한 수집에 연결된 공개 게시본이 없음. 빈 items |
| STALE | 게시 기준 시각이 현재보다 1시간 넘게 오래됐거나 미래임. 빈 items와 기준 시각으로 갱신 지연 안내 |
| READY | 유효한 성공 게시본으로 계산 완료. 조건을 만족하는 곡이 없으면 빈 items |

items는 `rank/song/increase/startViewCount/endViewCount/startObservedAt/endObservedAt`다. song은 기존 공개 SongCard다.

1. 공통 기준 T는 현재 view_publication의 publishedAt이다. SUCCEEDED 수집 작업과 연결된 게시본만 사용한다.
2. V(T)는 해당 게시본에 저장된 대표 영상 관측값이며 관측 시각이 `[T−1h,T]`에 있어야 한다.
3. V(T−24h)는 동일 대표 영상의 실제 시간별 표본 중 `[T−25h,T−24h]`에서 가장 가까운 이전 관측값이다. logicalSlot 대신 observedAt으로 판정한다.
4. 양쪽 표본이 있고 증가량이 양수인 공개 곡만 포함한다. 결측·허용 시간 밖·0·음수는 제외하며 원본을 변경하지 않는다. 신규 곡의 과거 값을 만들지 않는다.
5. 증가량 내림차순, 실제 공개일 내림차순, 곡 ID 내림차순으로 동점을 고정한다. 활동·졸업 멤버 모두 기존 공개 조건을 따른다.
6. REPEATABLE_READ 트랜잭션에서 한 SQL로 순위 계산을 완료한 후 카드 관계를 일괄 조회한다. 미완료 수집 표본을 V(T)에 사용하지 않으며 중간 결과를 게시하지 않는다. 파생 숫자를 별도 테이블이나 캐시에 저장하지 않는다.

## 설정·권한·운영 적용

`TRENDING_ENABLED=false`, `TRENDING_POLICY_ALLOWED=false`가 기본이다. 두 값이 true일 때만 계산·응답한다. 활성화만으로 정책 승인을 대신하지 않는다. [YouTube 파생 지표 공식 문서](https://developers.google.com/youtube/terms/derived-metrics-policy)의 추가 약관 적용·수락을 확인한 뒤 운영 설정을 변경한다. 현재 외부 확인이 완료됐다고 가정하지 않았다.

V13을 웹 마이그레이션 계정으로 적용하면 조회수 읽기 권한·표본 탐색 인덱스·영상 수집 시작 열을 추가한다. 이전 Flyway 파일은 변경하지 않는다. 웹 DB 역할은 표본 SELECT만 가능하고 숫자·시작 시각을 수정할 수 없다.

수집 실행 전에 스키마 소유자로 기존 collector-grants.sql과 [통계 수집 권한](../infra/sql/statistics-collector-grants.sql)을 적용한다. 수집 계정은 시작 열 UPDATE만 추가로 받으며 개인 정보나 관리자 편집 필드에 접근하지 않는다. 시간별 48시간·일별 오늘 포함 KST 30일의 기존 정리 정책을 유지한다. 백업에 표본을 넣지 않는 기존 기준도 유지한다.

잘못된 숫자 범위는 400 INVALID_STATISTICS_QUERY, 잘못된 UUID·숫자 형식은 기존 400 INVALID_CATALOG_QUERY다. 실제 사용자 계정·후보·운영 설정은 이 개발 단계에서 변경하지 않는다.

## 검증 명령

```sh
# backend 디렉토리, Java 21과 Docker 필요
./gradlew test --tests '*ViewStatisticsServiceTest' --no-daemon
./gradlew integrationTest --tests '*ViewStatisticsIntegrationTest' --tests '*StatisticsMigrationIntegrationTest' --tests '*VideoCollectorIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

## 로컬 검증 결과

2026-10-03. Java 21·역할 분리 PostgreSQL 17.6·Testcontainers에서 `GRADLE_USER_HOME=/tmp/stelody-gradle ./gradlew spotlessApply check bootJar --no-daemon` 성공. 단위 87개·통합 214개, 총 301개가 실패·오류·건너뜀 없이 통과했다.

KST 날짜·7/30일 범위·null 결측·실제 0·감소·한 점·미수집·만료/미래 값·대표 변경·공개 조건 404, 급상승의 정확한 1시간 경계·이전 표본 선택·동점·누락/0/음수·완료 게시본·부분 실패·지연·기본 비활성 및 양쪽 설정 필요, V12→V13 업그레이드·시작 시각 보완·웹 표본 변경 금지·수집 권한 분리·기존 일별 마지막 성공값과 보관 정리를 검증했다.

실제 YouTube API를 호출하거나 로컬 콘텐츠 DB를 갱신하지 않았다. 운영 급상승 공개는 정책 확인 및 명시적 활성화 후 진행한다. 관리자 화면·실제 자료 검수·YouTube 플레이리스트 내보내기는 후속 범위다.
