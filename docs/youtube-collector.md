# 등록 영상 수집

## 이번 단계

허용된 공식 채널의 **이미 등록된 영상**을 갱신한다. `channel.collection_enabled = true`이고 `channel_type`이 `GROUP` 또는 `MEMBER`인 채널만 대상이다. 실제 채널·곡을 임의로 등록하지 않으며 Testcontainers 자료로 검증한다.

- 영상별 조회수, 이용 가능 상태, YouTube 원본 제목·공개일·썸네일·길이·임베드 가능 여부
- 작업·시도·대상별 처리 기록, DB 중복 실행 방지, 실패 후 재개
- 완료된 수집 결과의 공개 조회수 게시
- 실제 관측의 시간별 저장과 KST 일별 마지막 성공 관측값

신규 영상 탐색·분류, 관리자 검토·재시도 버튼·상태 API, 조회수 차트·급상승 계산, YouTube 내보내기는 다음 기능 단위다. 운영 자동 분류·공개를 활성화하지 않는다.

## 실행 모드

같은 backend jar를 `--collector`로 실행한다. 명시적으로 구성한 별도 Spring 컨텍스트를 사용하여 HTTP 서버·JPA·Spring Security·Spring Session·Flyway를 시작하지 않는다. 서버 코드와 수집 코드가 같은 jar에 있지만 수집 실행은 서버용 DB 계정을 사용하지 않는다.

```sh
# backend 디렉토리에서 Java 21 사용
./gradlew bootJar --no-daemon
java -jar build/libs/stelody-0.0.1-SNAPSHOT.jar --collector
```

설정 전에는 `SKIPPED_DISABLED`, 종료 코드 0이다. 외부 호출·DB 접속도 하지 않는다. 실제 실행은 다음 환경변수가 모두 준비된 뒤 `COLLECTOR_ENABLED=true`로 켠다.

| 변수 | 용도 |
|---|---|
| COLLECTOR_ENABLED | 기본 false, 명시적으로 true일 때 실행 |
| COLLECTOR_DB_URL | PostgreSQL JDBC URL, 운영은 TLS 인증서 검증 |
| COLLECTOR_DB_USERNAME | 수집 전용 로그인, 풀러 사용 시 로그인 형식 확인 |
| COLLECTOR_DB_PASSWORD | 수집 전용 비밀번호 |
| YOUTUBE_API_KEY | YouTube Data API 키 |

Spring Boot는 `.env`를 자동으로 읽지 않는다. IntelliJ 환경변수 또는 실행 환경의 비밀 설정으로 주입한다. IntelliJ의 프로그램 인수에 `--collector`를 추가하면 수집 후 프로세스가 종료된다. 키·비밀번호를 공유 파일이나 프로젝트 실행 설정에 저장하지 않는다.

## DB 준비

1. 웹 서버의 마이그레이션 계정으로 V8을 적용한다. V1~V7는 변경하지 않는다. 수집 계정이나 API 키 없이도 V8 적용이 가능하다.
2. DB 관리 계정으로 `stelody_collector` 로그인을 별도로 생성한다. 비밀번호는 비밀 설정에 보관한다. `NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT`를 적용한다.
3. 스키마 소유자 계정으로 [권한 스크립트](../infra/sql/collector-grants.sql)를 실행한다. 실제 역할 이름이 다르면 스크립트의 역할 이름을 수정한 후 실행한다. 로그인 이름과 PostgreSQL 역할 이름은 풀러 구성에 따라 다를 수 있다.
4. 수집 실행에 전용 계정을 설정한다. 공유 개발 계정·웹 계정·마이그레이션 계정은 사용하지 않는다.

권한 스크립트는 `app.channel`, `app.video` 조회, 영상 source·상태 열 갱신, 수집·관측·공개 조회수 테이블의 필요한 작업만 허용한다. `song_entry`의 제목·노출·참여·대표 영상, `video.published_at`·`thumbnail_url`의 수동 표시값을 수정하지 못한다. 회원·즐겨찾기·개인 플레이리스트·세션 조회와 DDL도 허용하지 않는다.

수집기는 시작 시 개인 데이터 조회 또는 스키마 생성·관리 권한이 있으면 `COLLECTOR_ROLE_TOO_BROAD`로 중단한다. 공개 상태가 다시 확인되어도 관리자 `HIDDEN`을 바꾸거나 대표 영상을 자동 교체하지 않는다.

## API 키 준비

기존 Google Cloud 프로젝트에서 다음 순서로 준비한다.

1. **API 및 서비스 → 라이브러리**에서 **YouTube Data API v3**를 사용 설정한다.
2. **API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → API 키**로 키를 만든다.
3. 키의 **API 제한사항**을 YouTube Data API v3로 제한한다. 실행 네트워크가 확정되면 해당 환경에 맞는 애플리케이션 제한도 설정한다.
4. 로컬 실행 설정에 `YOUTUBE_API_KEY`로 주입한다.

이 키는 공개 영상 조회용이다. Google 로그인 OAuth 클라이언트 ID·비밀번호와 별개이며, 사용자 플레이리스트 내보내기 권한을 대신하지 않는다. 내보내기 추가 OAuth는 해당 기능에서 구현한다.

[영상 조회 API](https://developers.google.com/youtube/v3/docs/videos/list), [영상 리소스](https://developers.google.com/youtube/v3/docs/videos), [API 키 사용](https://docs.cloud.google.com/docs/authentication/api-keys-use), [API 오류](https://developers.google.com/youtube/v3/docs/errors)를 기준으로 구현한다. API 키는 `X-Goog-Api-Key` 헤더로 보내며 URL·작업 로그에 넣지 않는다. 실제 키와 공식 자료가 준비되기 전에는 WireMock으로 검증한다.

## 작업·잠금·재개

실행 슬롯은 UTC 시간 단위다. 작업마다 대상 영상·채널 ID를 고정하고 영상은 최대 50개씩 조회한다. 같은 시간에 성공한 작업은 재실행해도 다시 수집하지 않는다. 실패·할당량 중단·시간 초과 또는 중단된 작업이 최근 48시간 안에 있으면 가장 오래된 작업의 미처리 영상부터 재개한다. 완료된 영상은 다시 요청하거나 표본을 덮어쓰지 않는다. 재개 실행은 이전 슬롯을 유지하되 실제 `observedAt`은 새 응답 시각을 기록한다. 지난 시각의 조회수를 추정하지 않는다.

- PostgreSQL 세션 advisory lock으로 실행 중첩을 막는다.
- 잠금을 얻지 못하면 `SKIPPED_LOCKED`로 종료한다.
- 매 쓰기 트랜잭션은 DB의 소유 토큰도 확인한다. 잠금 연결이 끊겨 새 실행이 시작되면 이전 실행은 `LOCK_LOST`로 쓰기를 거절당한다.
- API 호출 중에는 DB 트랜잭션을 열어두지 않는다. 각 배치 반영과 최종 게시는 별도의 짧은 트랜잭션이다.
- 동결 후 허용 채널이 비활성화되거나 관계가 바뀐 영상은 `SKIPPED`로 기록한다. 요청 채널과 응답 채널이 다르면 배치 전체를 반영하지 않는다.

작업 상태는 `RUNNING`, `SUCCEEDED`, `FAILED`, `QUOTA_EXHAUSTED`, `TIMED_OUT`이다. 시도 횟수와 시작·종료 시각, 정규화된 오류 코드만 보관한다. 원문 API 오류·응답·키를 로그나 실패 테이블에 저장하지 않는다. 실패는 종료 코드 1이다. 비활성·중복·완료 재실행은 0이다.

## 실패와 영상 상태

연결 제한은 5초, 요청 제한은 15초, 실행 예산은 최대 10분이다. DB 문장은 최대 10초로 제한한다. HTTP 429·5xx와 일시적인 통신 실패는 최대 3회 시도하며 1초·2초 지수 백오프를 적용한다. 403 중 할당량 초과는 즉시 중단하고, 명시적인 속도 제한은 제한된 재시도를 적용한다. 나머지 4xx와 잘못된 응답은 재시도하지 않는다.

전체 HTTP 실패·타임아웃·잘못된 JSON·예상하지 않은 항목은 영상 삭제로 처리하지 않는다. 정상 목록 응답에서 요청한 영상이 빠진 경우 `UNAVAILABLE`로 기록한다. 공개 키만으로 비공개와 삭제를 구분할 수 있다고 가정하지 않는다. 명시적인 privacy/upload 상태가 있는 응답은 해당 상태를 기록한다. 예약 공개는 이용 불가로 두고 다음 실행에서 다시 확인한다. 조회수가 없는 경우 null로 유지하며 0을 만들어 넣지 않는다.

## 원본·관측·공개 결과

원본 메타데이터는 `source_*`에 저장한다. 곡 제목·관리자 노출 설정·참여 관계·대표 영상은 유지한다. 공개일·썸네일의 수동 표시값이 있으면 우선 사용하고, 없을 때 수집 source 값을 사용한다.

성공한 배치의 실제 관측은 즉시 저장한다. `(video_id, logical_slot)`로 재시도 표본 중복을 막는다. 일별 값은 관측 시각의 KST 날짜로 저장하며 해당 날짜의 마지막 성공 관측값만 갱신한다. 공개 정렬·카드 조회수는 전체 작업 성공 후 새 `view_publication`으로 생성하고 포인터를 한 트랜잭션에서 교체한다. 중간 실패는 기존 공개 조회수 버전을 유지한다. 개별 이용 불가 상태는 확인된 배치 반영 시 적용되어 탐색·개인 목록의 공개 조건에 반영된다.

빈 대상 작업은 기존 공개 조회수 포인터를 지우지 않는다. 공개 조회수는 영상별 값이며 대표 영상 하나만 공개 API가 사용한다. 복수 영상 조회수를 합산하지 않는다.

시간별 표본과 작업 대상에 남은 시간별 수치는 관측 후 48시간, 일별 값은 오늘을 포함한 KST 30일로 제한한다. 공개 최신값·수집 메타데이터·작업 기록도 30일 기준으로 정리한다. 수동 표시값은 보존한다. 게시 값이 만료되면 남은 유효 값으로 새 공개 버전을 만들어 커서를 무효화한다. 별도 정책 조건이 더 짧으면 보관 설정·구현을 함께 조정한다.

## 검증

```sh
# backend 디렉토리, Docker 실행 필요
./gradlew test --tests '*YouTubeVideoClientTest' --no-daemon
./gradlew integrationTest --tests '*VideoCollectorIntegrationTest' --tests '*CollectionMigrationIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

WireMock의 정상·누락·잘못된 응답·429·5xx·할당량·지연 응답과, PostgreSQL의 역할 분리·부분 실패 재개·중복 슬롯·잠금·소유 토큰·KST 일별 값·보관 정리·V7 업그레이드를 검증한다. 실제 YouTube 호출과 운영 DB 권한 적용은 외부 설정 후 확인한다.
