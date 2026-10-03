# 커버 자동 등록·공개

2026-10-03. 기획서 AC04·AC05를 구현한다. 사용자가 승인한 기준은 **제목에 가수가 명시된 단독 커버부터 자동 공개하고, 참여·콘텐츠가 불명확하면 검토로 남기는 것**이다. 공개 곡 조회는 로그인 없이 가능하다.

## 자동 공개 조건

- 수집을 허용한 GROUP 또는 MEMBER 채널의 공개 영상이고 공개일이 미래가 아니다.
- 관리자 분류 규칙의 제외 조건을 먼저 검사하고 COVER로 판정한다.
- 제목의 마지막 `/` 구간이 `멤버명 Cover`이거나, 제목 끝에 `Covered by 멤버명`이 있다. 제목 전체가 `멤버명 Cover`인 경우도 허용한다. 등록된 본명·별칭을 사용하며, `아오쿠모 린 (Aokumo Rin) Cover`처럼 같은 멤버의 별칭을 병기할 수 있다. 다른 텍스트가 가수 구간에 남으면 검토한다.
- 제목에 언급된 등록 멤버는 정확히 한 명이어야 한다. 중복 별칭이 서로 다른 멤버에 매칭되는 경우도 검토한다. MEMBER 채널은 관리자가 연결한 소유 멤버와 명시된 가수가 같아야 한다. GROUP 채널도 한 명의 명시된 가수만 연결하며 전체 멤버를 추정하지 않는다.
- feat/duet/collab/chorus/live/teaser/medley/remix, 합창·콜라보·듀엣·클립·쇼츠 등의 표기나 `&`, `+`, `×`, 단독 `x`가 있으면 자동 공개하지 않는다. Original·오리지널과 커버 표기가 충돌해도 검토한다. 이 기준은 보수적이므로 곡 제목 자체의 단어 때문에 검토로 남을 수 있다.
- `videos.list`에 `liveStreamingDetails`를 요청하고, 해당 객체가 없으며 `snippet.liveBroadcastContent=none`일 때만 자동 공개한다. `none`만으로 종료된 방송을 구분할 수 없다. 방송 메타데이터가 있으면 종료 시각 유무와 관계없이 `LIVE_BROADCAST_METADATA_PRESENT`로 검토하며, upcoming은 DEFERRED다. 불명확한 상태는 검토하고 잘못된 방송 메타데이터 형식은 `INVALID_RESPONSE`로 수집을 실패 처리한다. [YouTube 방송 필드](https://developers.google.com/youtube/v3/docs/videos#liveStreamingDetails)를 사용한다.
- 길이가 없거나 180초 이하면 `FULL_LENGTH_UNCONFIRMED`로 **검토**한다. 짧다는 이유만으로 Shorts나 제외 영상으로 확정하지 않는다. 현재 [YouTube Shorts 기준](https://support.google.com/youtube/answer/15424877?hl=en)은 길이와 화면 비율을 함께 사용하지만 이 수집기는 화면 비율을 확인하지 않으므로 자동 공개를 보류한다.
- 제목이 300자 이내여야 한다. 긴 제목은 임의로 잘라 공개하지 않고 검토한다.

설명·채널 주인만으로 가수나 원곡을 추정하지 않는다. 기본 판정에 맞지 않는 歌ってみた 표기, 합창·외부 참여 영상, 오리지널 영상은 기존 관리자 검수·등록 경로를 사용한다.

## 생성과 보존

후보 반영, COVER 곡 생성, 확인된 멤버 연결, OFFICIAL_COVER 대표 영상 지정, PUBLISHED 전환, 등록 기록 및 탐색 체크포인트를 **한 트랜잭션**으로 저장한다. 제목은 원본 제목을 임시 표시 제목으로 사용한다. `work_id=NULL`, `search_visibility=UNCHECKED`로 시작하며 원곡·노래방·별칭 정보가 없어도 공개한다. 임베드가 금지된 공개 영상도 링크를 제공할 수 있다.

YouTube 영상 ID의 유일성, 실행 잠금·소유 토큰, 후보 행 잠금을 함께 사용한다. 반복 실행·과거 탐색·동시 등록은 기존 곡을 복제하지 않는다. 중간 실패는 같은 페이지의 곡·후보·체크포인트를 전부 롤백하고 다음 실행에서 다시 처리한다. IGNORED·REGISTERED 후보와 기존 영상은 자동 변경하지 않는다. 관리자 제목·원곡·별칭·특별 문구·참여·대표 영상·HIDDEN 상태는 재수집으로 덮어쓰지 않는다.

`app.cover_auto_registration`은 후보·곡·영상·멤버 ID, 분류/참여 판정 규칙 버전(`title-v2:<설정 버전>/solo-credit-v1`), 사유, 처리 시각을 기록한다. 원본 제목은 기존 30일 갱신·만료 규칙을 따른다. 자동 기록에는 회원·개인 목록·세션 정보가 없다.

## 적용과 활성화

1. 웹 마이그레이션 계정으로 V19를 적용한다. 수집 역할이 없어도 마이그레이션할 수 있다. 기존 곡·무시 기록은 그대로 보존한다.
2. 스키마 소유자로 기존 수집·탐색·분류 권한과 [자동 공개 권한](../infra/sql/cover-publication-grants.sql)을 적용한다. 수집 역할에 일반 곡 INSERT/UPDATE나 개인 데이터 접근 권한을 추가하지 않는다. 새 함수의 EXECUTE만 허용한다. 웹 역할과 PUBLIC에는 이 함수의 실행 권한이 없다.
3. 관리 API로 멤버 본명·필요한 별칭·채널 관계를 입력한다. 공식 채널 초기 스크립트는 이를 추정하지 않으므로 관계가 없는 MEMBER 채널은 검토로 남는다.
4. 기존 개발 기준서의 정책 확인 후, 웹과 수집 환경에 `COVER_AUTO_PUBLICATION_POLICY_ALLOWED=true`를 설정한다. 수집에는 기존 `COLLECTOR_ENABLED`, `DISCOVERY_ENABLED`, `DISCOVERY_CLASSIFICATION_ALLOWED`도 필요하다. GitHub 수집 workflow는 같은 이름의 저장소 Variable을 전달한다.
5. 관리 API로 `enabled=true`를 저장한다. 정책 설정과 관리자 활성화는 모두 기본 false다. 제목 분류 권한만 켜도 자동 공개되지 않는다.

새 권한이 빠진 허용 실행은 원격 호출 전에 `COVER_PUBLICATION_GRANTS_MISSING`으로 실패한다. 정책 비허용 실행은 새 수집 권한 없이 후보 수집을 유지한다.

관리자가 `enabled=false`로 중단해도 영상 탐색·검토 후보 저장은 계속된다. 공개 함수는 저장 직전에 활성화 값을 다시 확인하고 잠근다. 이미 공개 트랜잭션이 진행 중이면 중단 요청은 해당 트랜잭션 종료 후 반영되며, **중단 응답 이후에는 새 공개가 시작되지 않는다**.

활성화 전에 쌓인 PENDING/REVIEW의 COVER·UNKNOWN 후보와 DEFERRED 후보는 신규 탐색 실행에서 채널당 최대 50개씩 원본을 새로 조회한다. 마지막 관측 2시간 이후의 후보를 오래된 순서로 처리한다. 오래된 저장 제목만으로 공개하지 않는다. IGNORED·등록 영상·EXCLUDED 제안은 이 재검사 대상에서 제외한다. 과거 탐색 실행은 지정 페이지의 후보만 처리한다.

## 관리자 API

모든 경로는 `/api/v1/admin/cover-auto-publication` 아래다. ADMIN 세션, 변경 시 CSRF가 필요하며 응답은 `Cache-Control: no-store`다. 비로그인 401, 일반 회원·권한 철회 403이다.

| 메서드·경로 | 동작 |
| --- | --- |
| GET `/` | `{enabled,version,policyAllowed}` |
| PUT `/` | 활성화·중단과 관리자 사유 기록 |
| GET `/audit?page=0&size=20` | 활성화·중단 변경 이력 |
| GET `/registrations?incompleteOnly=true&page=0&size=20` | 자동 등록 곡의 보완 목록 |

경로의 `/`는 접두 경로 자체를 뜻한다. PUT 예시:

```json
{"version":0,"enabled":true,"reason":"명시된 단독 커버 자동 공개 시작"}
```

`version`, `enabled`, 1~500자의 공백이 아닌 `reason`이 필수다. 오래된 버전은 409다. 정책 비허용 상태에서 활성화를 요청하면 503 `COVER_PUBLICATION_POLICY_DISABLED`를 반환하며 중단은 항상 허용한다. 설정과 감사 이력은 한 트랜잭션으로 저장한다.

보완 목록은 `{items,page,size,hasNext}`이며 size 1~50, page 0~10000이다. 각 항목은 `reviewId`, `songId`, `videoId`, 현재 관리자 제목·공개 상태, 적용 규칙·사유·시각, `missingFields`를 제공한다. songId로 기존 `/api/v1/admin/songs/{songId}` 편집 API를 사용한다.

`missingFields`는 `work`, `originalArtists`, `aliases`, `searchCheck`, `TJ`, `KY`다. 노래방은 곡 또는 연결 작품에서 REGISTERED/NOT_LISTED를 확인하면 보완된 것으로 본다. 별칭은 선택 정보이므로 별칭만 없으면 기본 보완 목록에서 빠진다. `incompleteOnly=false`로 전체 자동 등록 이력과 선택 누락도 조회할 수 있다. 수동 보완 결과를 실시간으로 계산하며 수집 원본으로 되돌리지 않는다.

## 검증

- 명시된 단독 본명·별칭 및 GROUP 채널 단독 크레딧의 자동 공개, 로그인 없는 공개 목록 노출
- 원곡·부가정보 없이 공개, 졸업 멤버 포함, 임베드 금지 링크 유지
- 참여·콘텐츠·짧은 길이·라이브·예약·미래 공개일·제외 규칙의 보류
- `liveBroadcastContent=none`인 종료 방송의 신규 탐색·재검사 시 공개 차단, 종료 시각 없는 방송 객체 감지, 잘못된 방송 메타데이터 응답 거부
- 정책/분류/관리자 활성화 각각의 차단, 원격 요청 중 중단, 기존 후보의 새 관측
- 반복·과거 탐색·동시 함수 호출 중복 방지, 등록 기록 실패 시 전체 페이지 롤백·재시도
- 원본 갱신 후 관리자 값·HIDDEN 보존, 무시 기록 유지
- 관리자 인증·CSRF·역할 철회·입력·버전 충돌·감사 롤백·보완 목록
- 웹/수집/마이그레이션 역할 분리, 수집 역할 없는 V18→V19 업그레이드

```sh
./gradlew test --tests '*CoverPublicationRulesTest' --tests '*DiscoveryOptionsTest' --tests '*YouTubeVideoClientTest' --no-daemon
./gradlew integrationTest --tests '*DiscoveryCollectorIntegrationTest' --tests '*CoverPublicationManagementIntegrationTest' --tests '*CoverPublicationMigrationIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

실제 로컬·운영 DB에 곡을 공개하거나 정책·관리자 설정을 활성화하는 작업은 수행하지 않았다. 구현은 모의 YouTube API와 역할을 분리한 PostgreSQL로 검증한다.

### 로컬 검증 결과

`GRADLE_USER_HOME=/tmp/stelody-gradle ./gradlew spotlessApply check bootJar --no-daemon` 성공. 종료 방송 판정 수정 후 단위 138개·PostgreSQL 통합 349개, 총 487개 테스트가 실패·건너뜀 없이 통과했다. 수정 전 모의 API의 종료 방송 응답으로 곡 1개가 자동 공개되는 문제를 재현했고, 수정 후 신규 탐색과 재검사 모두 곡·영상·자동 등록 기록을 생성하지 않고 검토 대기로 남는 것을 확인했다. 최초 구현 시 actionlint 1.7.7로 수집 workflow 검사도 통과했다. 로컬에 ShellCheck가 없어 해당 검사는 제외했다. `git diff --check`를 확인했다.
