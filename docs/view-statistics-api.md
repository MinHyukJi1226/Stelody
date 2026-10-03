# 조회수 추이 API

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

## 권한·운영 적용

V13을 웹 마이그레이션 계정으로 적용하면 일별 조회수 읽기 권한·영상 수집 시작 열을 추가한다. 이전 Flyway 파일은 변경하지 않는다. 웹 DB 역할은 표본 SELECT만 가능하고 숫자·시작 시각을 수정할 수 없다.

수집 실행 전에 스키마 소유자로 기존 collector-grants.sql과 [통계 수집 권한](../infra/sql/statistics-collector-grants.sql)을 적용한다. 수집 계정은 시작 열 UPDATE만 추가로 받으며 개인 정보나 관리자 편집 필드에 접근하지 않는다. 시간별 48시간·일별 오늘 포함 KST 30일의 기존 정리 정책을 유지한다. 백업에 표본을 넣지 않는 기존 기준도 유지한다.

잘못된 숫자 범위는 400 INVALID_STATISTICS_QUERY, 잘못된 UUID·숫자 형식은 기존 400 INVALID_CATALOG_QUERY다. 실제 사용자 계정·후보·운영 설정은 이 개발 단계에서 변경하지 않는다.

## 검증 명령

```sh
# backend 디렉토리, Java 21과 Docker 필요
./gradlew integrationTest --tests '*ViewStatisticsIntegrationTest' --tests '*StatisticsMigrationIntegrationTest' --tests '*VideoCollectorIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

## 로컬 검증 결과

2026-10-03. 조회수 추이와 수집 시작 기록만 포함한 커밋 구성에서 Java 21·역할 분리 PostgreSQL 17.6·Testcontainers로 `GRADLE_USER_HOME=/tmp/stelody-gradle ./gradlew spotlessApply check bootJar --no-daemon` 성공. 단위 84개·통합 206개, 총 290개가 실패·오류·건너뜀 없이 통과했다.

KST 날짜·7/30일 범위·null 결측·실제 0·감소·한 점·미수집·만료/미래 값·대표 변경·공개 조건 404, V12→V13 업그레이드·시작 시각 보완·웹 표본 변경 금지·수집 권한 분리·기존 일별 마지막 성공값과 보관 정리를 검증했다. 실제 YouTube API와 로컬 콘텐츠 DB는 변경하지 않았다.
