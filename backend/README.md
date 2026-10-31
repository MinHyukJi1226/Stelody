# Stelody 백엔드

스텔라이브 노래 정보의 검색·개인 목록·수집 API를 제공하는 Spring Boot 서버입니다.

## 로컬 실행

Java 21과 실행 중인 Docker가 필요합니다. 저장소 루트에서 실행합니다.

```sh
docker compose -f infra/compose.yml up -d
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

- 서버: <http://localhost:8080>
- 상태 확인: <http://localhost:8080/actuator/health>
- Swagger: <http://localhost:8080/swagger-ui.html>
- Google 로그인은 OAuth 클라이언트 환경변수와 `local,google` 프로필을 추가해야 합니다. [아래 로그인 설정](#상세-설정)를 참고하세요.

## 검증

`backend/`에서 실행합니다.

```sh
./gradlew test                 # 단위 테스트
./gradlew check bootJar        # 포맷·단위·DB 통합 테스트·실행 jar
```

전체 검증에는 Docker가 필요합니다. 통합 테스트는 별도 PostgreSQL에서 실행합니다.

## 운영

| 항목 | 주소·방식 |
| --- | --- |
| API 문서 | [Swagger](https://stelody-backend.onrender.com/swagger-ui.html) · [OpenAPI JSON](https://stelody-backend.onrender.com/v3/api-docs) |
| 서버·DB | Render · Supabase PostgreSQL |
| 조회수 갱신 | Spring 예약 실행, 한국 시간 매시간 정각 |
| 신규 영상 탐색 | 한국 시간 매일 00:00, 조회수 갱신 후 실행 |
| 신규 영상 알림 | 활성화 시 YouTube Hub 알림 수신 후 해당 영상만 확인·후보 저장 |
| DB 백업 | GitHub Actions에서 매시간 47분 확인, 유효한 백업이 없거나 24시간이 지나면 생성 |

신규 영상 탐색은 검토 후보를 수집합니다. 신규 커버 자동 공개·급상승·기념일 후보 기능은 정책 확인 전까지 비활성으로 유지합니다. YouTube 연결은 Google OAuth 테스트 사용자 범위에서 사용합니다.

서버 중지·절전이나 작업 지연으로 실제 실행은 늦어질 수 있습니다. Render 내부 수집의 실패 이메일은 현재 연결하지 않았으며 관리자 수집 상태와 서버 로그에서 확인합니다.

## 상세 설정

필요한 항목만 펼쳐서 확인하세요. API 요청·응답은 Swagger를 기준으로 합니다.

<details>
<summary>Google 로그인 · API 연동</summary>

- Google Cloud의 **Web application** OAuth 클라이언트에 로컬 `/api/v1/auth/callback/google` URI를 등록하고 사용할 계정을 Test users에 추가합니다.
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`을 실행 환경에 주입하고 `local,google` 프로필로 실행합니다. Spring Boot는 `.env`를 자동으로 읽지 않습니다. [.env.example](../.env.example)은 형식 참고용입니다.
- Swagger에서 인증하려면 같은 브라우저로 `/api/v1/auth/google?returnTo=%2Fswagger-ui.html`에 이동해 로그인한 뒤 GET `/api/v1/auth/csrf`의 token을 **Authorize → csrf**에 입력합니다.
- 개인 API는 SESSION 쿠키, 관리자 API는 ADMIN 역할이 필요합니다. 변경 요청에는 CSRF 응답의 `headerName`·`token`을 전달하고 로그인·재인증·로그아웃 뒤에는 새 토큰을 받습니다.
- 플레이리스트·관리 설정 변경은 현재 `version`을 전달합니다. 409이면 최신 내용을 다시 조회합니다. 다른 회원의 목록은 404이며 오류는 Problem JSON의 `code`·`status`·`traceId`로 처리합니다.
- 즐겨찾기는 최근 저장순(`savedAt` 내림차순, 동률이면 `songId` 내림차순)만 지원합니다. 중복 저장은 순서를 유지하고 해제 후 재저장은 맨 앞으로 이동합니다. 이용 불가 항목도 순서를 유지하며 다음 페이지에 기존 `nextCursor`를 그대로 전달합니다. 페이지 크기는 변경할 수 있고, 조회 중 새 저장 항목은 첫 페이지를 새로 조회합니다.
- GET `/api/v1/me/playlists?songId=<UUID>`는 각 목록의 `containsSong`으로 곡 저장 여부를 반환합니다. 이용 불가 곡도 이미 저장되어 있으면 `true`, 미저장·존재하지 않는 UUID는 `false`입니다. `songId` 생략 및 생성·상세·변경 응답은 `null`입니다. 목록 정렬·커서·개수는 그대로이며, 추가 요청의 `version`·중복 검증은 계속 사용합니다.
- 전체 순서 편집은 GET `/api/v1/me/playlists/{id}/items`의 모든 페이지를 같은 `version`으로 모은 뒤 PUT `/api/v1/me/playlists/{id}/order`에 그 버전과 원하는 순서의 전체 `itemIds`를 전달합니다. `songId`가 아닌 항목 `id`이며 이용 불가 항목도 포함합니다. 누락·중복은 400, 조회·저장 중 `409 PLAYLIST_CHANGED`이면 첫 페이지부터 다시 읽고 편집 내용을 확인합니다. 성공 시 새 버전을 사용합니다.
- 로컬 프론트 → 운영 API 연결은 `GOOGLE_LOCAL_REDIRECT_URI=http://localhost:5173/api/v1/auth/callback/google`로 활성화합니다. Google Web 클라이언트에도 같은 승인된 리디렉션 URI를 등록합니다. 미설정 시 비활성이고 서버는 localhost·127.0.0.1 콜백만 허용합니다.
  - 로그인 시작: `/api/v1/auth/google?loginClient=local&returnTo=%2Flogin`. `loginClient` 생략 시 기존 운영 로그인입니다. 임의 값·중복·비활성 연결은 400 `INVALID_LOGIN_CLIENT`입니다. `returnTo`의 내부 경로 제한은 유지합니다.
  - 프론트: API 기본 주소 `/api/v1`, 개발 프록시 대상 `https://stelody-backend.onrender.com`. `/api` 전체(시작·authorize·callback·세션·CSRF·로그아웃)를 같은 프록시로 전달합니다. 쿠키의 Secure·HttpOnly·SameSite·Path는 개발 프록시에서도 유지합니다. 브라우저의 localhost 지원을 사용하고, 쿠키가 차단되는 환경에서는 로컬 HTTPS를 사용합니다. 운영 서버의 Secure 쿠키와 CORS 설정은 변경하지 않습니다.
  - 복귀: 성공·실패·취소 후 선택된 로컬 origin의 `returnTo`로 이동합니다. 생략 시 성공은 로컬 `/api/v1/me`, 실패는 기존 401 JSON입니다. 선택은 OAuth state와 함께 5분간 저장하며 Host/Forwarded 헤더·콜백 쿼리로 변경할 수 없습니다. 실제 로그인은 `/me`로 확인합니다.
  - 지원 범위: `loginClient`는 일반 Google 로그인만 선택합니다. YouTube 연결은 아래 별도 `callbackClient`를 사용하며 탈퇴 재인증은 기존 운영 콜백을 사용합니다. Google 콘솔 등록·운영 환경변수·프론트 프록시 설정 후 실제 계정으로 검증합니다.
- 로그인 `returnTo`는 `/`로 시작하는 내부 UI 경로만 허용합니다. 최대 2,048자이며 외부 주소·경로 우회·`/api`·`/actuator`는 거절합니다. 같은 브라우저에서 5분 안에 완료해야 합니다. 생략하면 `/api/v1/me`로 이동합니다. `loginResult`는 안내용이며 실제 로그인은 `/api/v1/me`로 확인합니다.
- 세션은 비활동 30분·생성 후 최대 12시간입니다. **일반 USER는 최초 Google 로그인부터 30일간 자동 로그인**합니다. 별도 `STELODY_LOGIN` 쿠키(Secure·HttpOnly·SameSite=Lax·Path=/)를 검증해 다음 API 요청에서 새 세션을 만들며, 복구·탈퇴 재인증은 30일 기한을 연장하지 않습니다. 관리자에게는 발급하지 않습니다. 로그아웃은 현재 기기의 자격과 복구 세션을 폐기하며 다른 기기는 유지합니다. 계정 삭제·상태·역할 변경은 모든 자격을 폐기합니다.
- 프론트는 기존 세션·CSRF API를 사용하고 모든 `Set-Cookie`를 프록시에서 그대로 전달합니다. 짧은 세션 복구에는 기존 유효 CSRF 헤더를 사용할 수 있지만, 로그인·명시적 재인증·로그아웃 뒤에는 다시 조회합니다. 503 `LOGIN_STORAGE_UNAVAILABLE`은 확인·복구·폐기 실패이며 로그인 해제나 변경 성공으로 처리하지 않습니다. 30일 만료·쿠키 삭제 후에는 Google 로그인이 필요합니다.
- V25는 로그인 유지 자격 테이블과 계정 변경 시 폐기 트리거를 추가합니다. `SPRING_FLYWAY_ENABLED=false`인 운영에는 **새 코드 배포 전에 전용 마이그레이터로 V25를 적용**합니다. 런타임 역할만 자격 테이블에 접근하며 복구 시 자격은 제외됩니다.
- **탈퇴 결과 확인:** 최종 DELETE 전에 POST `/api/v1/me/withdrawal-requests`(로그인·CSRF, 본문 없음)로 `{id, queryToken, expiresAt}`을 발급받아 같은 탭 `sessionStorage`에 저장합니다. 발급 후 24시간이며 연장하지 않습니다. DELETE `/api/v1/me`에 선택 `Withdrawal-Request-Id: id`를 전달하면 한 번만 접수하며 로그인·CSRF·재인증 조건은 그대로입니다. 헤더 생략 시 기존 DELETE 계약을 유지하지만 사후 조회는 제공하지 않습니다.
  - GET `/api/v1/withdrawal-requests/{id}` + `X-Withdrawal-Token`은 세션·계정 삭제 후에도 해당 요청만 읽습니다. 토큰은 조회 전용이며 URL·로그에 넣지 않습니다. 서버에는 해시만 저장하고 개인정보를 응답하지 않습니다.
  - `COMPLETED`: 계정 삭제와 같은 트랜잭션으로 확정. `PROCESSING`: 접수·처리 중. `NOT_COMPLETED`: 확정된 업무 거절(`code`). `UNKNOWN`: 미접수(`WITHDRAWAL_NOT_SUBMITTED`)·커밋 불확실·처리 중단 등 확인 불가. 처리 시작 후 2분을 넘긴 PROCESSING도 UNKNOWN으로 안내하며 후속 완료가 확인되면 바뀔 수 있습니다.
  - PROCESSING만 `pollAfterSeconds=3`·`Retry-After: 3`에 맞춰 재조회합니다. 새로고침은 저장한 같은 ID·토큰을 사용합니다. 완료·확정 미완료·만료 시 저장값을 삭제합니다. 응답 유실·UNKNOWN에는 자동 DELETE나 새 ID 반복 접수를 하지 않습니다. 미접수는 요청이 아직 전송 중일 수 있어 확정 미완료로 처리하지 않습니다.
  - 400 `INVALID_WITHDRAWAL_REQUEST_ID`·`INVALID_WITHDRAWAL_TOKEN`, 404 `WITHDRAWAL_RESULT_NOT_FOUND`(기록 없음·잘못된 권한), 410 `WITHDRAWAL_RESULT_EXPIRED`, 503 `WITHDRAWAL_RESULT_STORAGE_UNAVAILABLE`을 구분하고 완료로 처리하지 않습니다. DELETE 409 `WITHDRAWAL_REQUEST_UNAVAILABLE`은 없거나 다른 계정·만료·이미 사용한 ID입니다. 결과 기록은 만료 후 정리하고 백업·복구에서 제외합니다. V26 결과 테이블이 필요하므로 자동 마이그레이션이 꺼진 운영에는 **새 코드 배포 전에 V26을 적용**합니다. 추가 환경변수는 없습니다.
- 탈퇴는 POST `/api/v1/me/reauthentications`로 같은 계정을 재인증한 뒤 새 CSRF로 5분 내 DELETE `/api/v1/me`를 호출합니다. 철회·탈퇴 기록 저장 실패 시 계정은 유지됩니다.
  - 선택 JSON `{"returnTo":"/확정된-탈퇴-화면","callbackClient":"local"}`을 보내고 응답 `authorizationUrl`로 브라우저를 이동합니다. `returnTo`는 최대 2,048자 내부 UI 경로만, `callbackClient`는 `default`(기본)·`local`만 허용합니다. 본문·필드·null 생략을 지원합니다. `returnTo` 생략 + default는 기존 콜백·확인 JSON 복귀를 유지하고, local에서 경로를 생략하거나 신뢰할 복귀 정보가 사라지면 `/api/v1/me/withdrawal-confirmation`으로 돌아갑니다.
  - 새 프론트 복귀용 기본 콜백: `GOOGLE_REAUTHENTICATION_REDIRECT_URI=<같은 세션을 쓰는 공개 origin>/api/v1/auth/callback/google-reauthentication`. 프론트·API가 프록시로 연결되면 콜백도 같은 공개 `/api` 프록시를 사용합니다. `GOOGLE_REAUTHENTICATION_FRONTEND_ORIGIN=https://<프론트 도메인>`으로 복귀 origin을 지정할 수 있고 미설정 시 상대 경로로 복귀합니다. 도메인·연결 방식은 배포 준비 때 함께 확정합니다.
  - 로컬 콜백: `GOOGLE_REAUTHENTICATION_LOCAL_REDIRECT_URI=http://localhost:5173/api/v1/auth/callback/google-reauthentication-local`. Google Web OAuth 클라이언트 승인 URI에 새 기본·로컬 콜백을 모두 등록합니다. 기존 Google 로그인·YouTube 연결 설정과 별개이며 운영 값은 배포 준비 때 설정합니다. 시작·콜백·확인·CSRF·최종 탈퇴는 같은 `/api` 프록시 세션을 사용하고 모든 `Set-Cookie` 및 Secure·HttpOnly·SameSite를 유지합니다.
  - 새 흐름은 303으로 `reauthResult=success/cancelled/failed`, 취소·실패 시 허용 `reauthError`를 전달합니다. 원문 코드·토큰·state·오류는 URL에 넣지 않습니다. 잘못된 state·세션 만료로 저장 경로를 신뢰할 수 없으면 등록된 origin의 기본 확인 API로 복귀합니다. 오류 코드는 OpenAPI 콜백 설명을 참고합니다.
  - 복귀 값은 안내용입니다. GET `/api/v1/me/withdrawal-confirmation`의 실제 상태·만료를 확인하고 CSRF를 다시 조회한 뒤, 사용자가 최종 버튼을 누를 때만 DELETE합니다. 재인증·5분 유효기간은 30일 로그인 유지 기한을 연장하지 않습니다. 세션이 만료된 OAuth 요청을 자동 로그인으로 되살리지 않습니다.
  - 탈퇴 409: `REAUTHENTICATION_REQUIRED`(재인증 없음·만료·세션 변경), `YOUTUBE_EXPORT_BUSY`(작업 중), `YOUTUBE_REVOCATION_PENDING`(연결 철회 대기). 503: `ACCOUNT_STORAGE_UNAVAILABLE`·`WITHDRAWAL_JOURNAL_UNAVAILABLE`·`LOGIN_STORAGE_UNAVAILABLE`. 완료로 표시하거나 자동 탈퇴 재요청하지 않습니다. 401·403은 기존 로그인·CSRF 계약입니다.
  - 복귀 정보는 기존 세션 OAuth 요청에 저장하므로 DB 마이그레이션은 없습니다.
- 조회수는 대표 영상 하나의 실제 관측값이며 미관측 날짜는 null입니다. 커버는 공식 커버 영상, 오리지널은 공식 MV 또는 MV가 없을 때 공식 음원을 대표로 사용합니다. 영상 간 조회수를 합산하지 않습니다.
- 공개 곡 검색 `q`는 공백·영어 대소문자를 구분하지 않습니다. 곡·원곡 제목과 별칭, 참여 멤버·아티스트 이름과 별칭에 같은 규칙을 적용하며 표시 제목은 검색 정규화와 별개입니다. `q`는 기존처럼 원문 최대 200자이고 빈 검색어는 빈 결과입니다.
- GET `/api/v1/songs/{id}`의 `relatedSongs`는 같은 `work.id`의 다른 `COVER` 중 현재 곡에 없는 확정 멤버가 한 명 이상 참여한 곡만 반환합니다. 일반 공개 목록과 같은 노출·대표 영상 조건을 적용하고 곡 ID 중복 없이 조건 적용 후 `publishedAt`·곡 ID 내림차순 최대 6개입니다. 원곡 정보·일치하는 커버가 없으면 `[]`이며 페이지 조회는 지원하지 않습니다.
- 곡별 검색 별칭은 관리자 `GET /api/v1/admin/songs/{id}`의 `aliases`에서 조회합니다. 신규 등록 POST·기존 수정 PUT에 전체 별칭 배열(최대 50개, 각 300자)을 보냅니다. 삭제할 별칭을 빼고 저장하며 `[]`는 모두 삭제합니다. `version`·변경 사유를 함께 전달하고 `title`은 따로 관리합니다.
- 원곡 수정은 기존 PUT `/api/v1/admin/works/{id}`를 사용합니다. 링크·노래방 입력값이 같으면 배열 순서와 관계없이 행 ID·`checkedAt`(미확인 `null` 포함)을 보존하므로 아티스트 정정으로 확인 시각이 갱신되지 않습니다. 실제 추가·변경된 자료만 새로 저장하고 배열에서 빠진 자료는 삭제합니다. 전체 입력·`version`·변경 사유와 기존 관리자 권한·CSRF·감사 계약을 유지합니다.
- 곡 검색·멤버별 곡 목록의 `totalCount`는 검색어·필터·공개 조건에 일치하는 전체 고유 곡 수입니다. 페이지 크기·정렬·커서와 무관하며 빈 검색·결과 없음은 `0`입니다. `items.length`는 현재 페이지 크기이고 무한 스크롤은 기존 `nextCursor`·`hasNext`를 사용합니다. 요청 사이 카탈로그가 바뀌면 전체 개수도 달라질 수 있습니다.
- GET `/api/v1/songs/years`는 일반 탐색과 같은 공개 조건을 만족하는 곡의 검색 가능 연도(1900~2100)를 `{"years": [2026, 2025]}`로 제공합니다. 대표 영상 공개일의 한국 시간 기준이며 중복 없이 내림차순, 곡이 없으면 빈 배열입니다. 현재 검색어·유형·멤버 필터와 무관한 전체 선택지로 사용합니다.
- 멤버 목록은 활동 중 → 졸업 순이며, 각 상태 안에서 기수·지정 표시 순서 오름차순(미지정은 데뷔일·이름·ID 순)입니다. 기수·표시 순서·데뷔일 미등록은 해당 그룹 마지막입니다. 이전 이름순 커서는 400 `INVALID_CATALOG_QUERY`이므로 첫 페이지부터 다시 조회합니다.
- 공개 멤버 목록·상세와 관리자 멤버 등록·수정·상세의 `unitName`은 기수와 별도인 유닛명, `chzzkUrl`·`xUrl`은 HTTPS 링크입니다. 미등록이면 `null`, YouTube 채널이 없으면 `channels: []`입니다. 관리자 PUT에서 새 필드를 생략하거나 null·빈 문자열로 보내면 기존 값을 지우므로 유지할 값은 함께 전달합니다.
- V20에는 운영자가 제공한 기존 11명 프로필 초기값이 포함됩니다. 카탈로그의 멤버 ID가 일치할 때만 적용하며 멤버를 새로 생성하지 않습니다. 이후 변경은 관리자 API를 사용합니다.
- V21은 ABYSS 4명과 수집용 공식 채널을 등록하고 기존 11명의 데뷔일·생일을 보완합니다. 새 멤버 프로필 이미지는 null이며, 동일 이름·채널이 이미 등록돼 있으면 중복 생성 대신 마이그레이션이 실패하므로 먼저 등록 상태를 확인합니다.
- GET `/api/v1/admin/songs`는 기존 `id/name/version/status`에 `participants`, `discoveredAt`, `missingFields`, `informationComplete`를 제공합니다. 참여자는 멤버·외부 아티스트 순서이며 미확정 항목도 `confirmed=false`로 표시합니다. 발견일은 연결된 영상 후보의 최초 발견 시각이고 기록이 없으면 `null`입니다. 자동 등록 목록의 누락 코드 6개에 `members/representativeVideo`를 더하며, 선택 별칭(`aliases`)만 없으면 완성입니다. 공개 설정(`status`)과 완성도는 독립적이며 전체 곡에 같은 기준을 적용합니다.
- GET `/api/v1/admin/inbox`는 신규 검토(`PENDING+REVIEW`), 자동 등록 곡의 정보 보완, 기념일 `PENDING` 후보, 최근 24시간 미해결 수집 실패 작업 수를 반환합니다. 보완 완료·검토 처리·해당 원본 작업 재시도 성공 시 집계에서 제외합니다. 기념일 후보는 만료·비활성 근거도 재검토 대상으로 포함합니다. 실패 목록은 `/api/v1/admin/collection-failures`에 응답의 `failureWindowStart/checkedAt`을 `from/to`로 전달하면 같은 기간으로 조회합니다. 집계 단위·누락 조건은 OpenAPI에 명시하며 기존 설정·수집 현황 API를 함께 사용합니다.
- 아티스트 등록 응답 유실 복구: 프론트가 POST 전에 UUID와 원래 본문(`version: 0`, `name`, `aliases`, `reason`)을 보관하고 `POST /api/v1/admin/artists`의 선택 `Idempotency-Key` 헤더로 UUID를 보냅니다. 최초 성공은 기존 응답 형태로 201, 같은 ID·본문 재전송은 동일 아티스트 ID의 현재 정보와 중복 후보를 200으로 반환하며 `Idempotency-Replayed: true`입니다. 같은 ID·다른 본문은 409 `ARTIST_REGISTRATION_REQUEST_CONFLICT`, 처리 중 재전송은 409 `ARTIST_REGISTRATION_PROCESSING`입니다. 헤더 생략은 기존 등록 방식이며 결과 복구·중복 방지를 제공하지 않습니다.
- GET `/api/v1/admin/artist-registrations/{requestId}`는 등록한 관리자 본인만 로그인 후 조회합니다. `NOT_PROCESSED/PROCESSING/COMPLETED/FAILED`와 완료 시 `artistId`, 실패 시 `errorCode`를 반환하며 처리 중 재조회 간격은 3초입니다. 인증·CSRF·본문 검증 등 접수 전 거절은 본인 기록이 없을 수 있고, 조회 장애·401·403·503을 미처리로 취급하면 안 됩니다. 실패한 요청은 같은 ID로 다시 실행하지 않습니다. 새로고침 후에도 보관한 ID·본문으로 확인·재전송하며 새 ID로 반복 접수하지 않습니다.
- 결과 조회 기간은 최초 접수 후 24시간이며 연장되지 않습니다. 만료 조회·재전송은 410 `ARTIST_REGISTRATION_RESULT_EXPIRED`; 만료 결과 상세는 정리하고 중복 방지용 UUID·본문 해시·소유자·접수/만료 시각은 유지합니다. 2분 지난 비활성 처리 중 요청은 1분 간격 정리에서 `FAILED/ARTIST_REGISTRATION_INTERRUPTED`가 되며 실행 중 잠긴 요청은 보존합니다. 아티스트·감사·완료 기록은 같은 트랜잭션입니다. V28은 요청 기록과 최소 런타임 권한을 추가하므로 운영 자동 마이그레이션이 꺼져 있으면 배포 전에 적용합니다. 백업에는 중복 방지 기록을 보존하고 복원 시 만료 결과를 정리·중단된 진행 상태를 실패로 전환합니다.
- 후보 목록 GET `/api/v1/admin/reviews`는 `status=PENDING`이 기본이며 `ALL`은 전체 검토 상태를 조회합니다. 전체 조회에도 `disposition`(만료 출처는 DEFERRED)·페이지 조건·정렬을 동일하게 적용합니다. 목록·상세의 `registeredSongId`는 `registeredVideoId` 영상에 현재 연결된 곡 ID(UUID 또는 null)이며 미등록 후보는 null입니다.
- 신규 검토는 후보 선택 → 등록·삭제 예정 수 확인 → 실행 순서로 처리하며 곡별 입력 단계는 없습니다. GET `/api/v1/admin/reviews/completion`의 `items`(ID·버전, 최대 10000건)와 `requestId`, `selected`(1~100건)를 POST 같은 경로로 보내면 선택 곡 생성·대표 영상 연결·미선택 후보/종속 이력 삭제를 한 트랜잭션으로 처리합니다. 하나라도 실패하면 모두 취소하고, 확인 후 들어온 새 후보·등록 곡·보류/제외 후보·수집 지점은 유지합니다. 선택 없는 삭제는 허용하지 않고 수집 직후 자동 삭제도 하지 않습니다.
- 등록 요청은 `requestId`, 최초 검토 대상 전체의 `items: [{id,version}]`, `selected: [{id}]`(1~100건)만 전달합니다. 곡별 `SongInput`·`kind`·사유 입력은 없습니다. 서버가 수집 제안과 현재 분류 규칙으로 COVER/ORIGINAL을 결정하고 후보 영상을 대표 영상으로 연결합니다. 재판정 후에도 유형 미확정이면 기본 COVER 초안으로 등록하고 추후 수정합니다. 이 기본값은 선택 등록에만 적용하며 수집 분류·자동 공개 정책은 유지합니다. 분류 충돌·빈/초과 길이 제목은 `409 CANDIDATE_CLASSIFICATION_REQUIRED` 및 `selected[i].id`의 사유 코드로 전체 취소합니다.
- 제목은 NFKC·공백 정리 후 확인된 솔로 커버 크레딧만 제거하고 나머지는 영상 제목 초안을 유지합니다. 기존 솔로 크레딧과 채널 연결이 일치할 때만 멤버를 확정하며 단체·듀엣·외부 참여 등은 빈 참여자 배열로 남깁니다. COVER 영상은 OFFICIAL_COVER, ORIGINAL은 명시된 MV/음원 표식에 따라 OFFICIAL_MV/AUDIO, 불명확하면 OTHER입니다. 후보에 자동 공개에 필요한 방송 메타데이터가 없으므로 **DRAFT**로 등록하고 기존 자동 공개 설정은 유지합니다. 확정하지 못한 원곡·별칭·곡별 링크·노래방·기념/협업 정보는 비워 둡니다. 기존 관리자 곡 목록·상세의 `missingFields`로 보완하고 수정 API로 공개합니다. 솔로 자동 등록 전용 목록에는 추가하지 않습니다.
- 선택한 커버의 원곡은 등록 시 설명란(`snippet.description`)의 명시적 원곡 크레딧·YouTube 링크 → 원곡자·링크 크레딧과 일치하는 기존 원곡 제목·별칭 → YouTube 제목 검색 순서로 보완합니다. 검색은 첫 페이지 최대 5건에서 곡명과 아티스트/채널 일치 또는 배급사 자동 생성 Topic 크레딧을 확인하며, 다른 커버·동명곡·서로 다른 원곡 후보는 자동 확정하지 않습니다. 일반 Original 표기의 곡명을 가수로 사용하지 않고 믹싱·일러스트 등 제작 크레딧도 제외합니다. 기존 원곡은 같은 원곡 영상 ID 또는 일치하는 원곡자 크레딧으로 재사용하며 제목·별칭만으로 합치지 않습니다. 원곡자를 모르는 새 원곡 영상은 별도 미완성 원곡으로 저장합니다. 일반 Original 텍스트는 필수 가수 검색 조건으로 쓰지 않고 명시적 원곡자만 가수 조건에 적용합니다. 기존 아티스트는 재사용하고 빈 아티스트·YouTube 링크만 보완하며 기존 제목·별칭·크레딧·링크·노래방은 보존합니다. 근거 URL은 `sourceUrl` 또는 감사 기록에 남기고 설명란 원문은 저장하지 않습니다.
- 원곡 외부 조회는 기존 `YOUTUBE_API_KEY`를 사용하며 묶음 전체 최대 20초, 동일 제목 검색은 첫 페이지 1회입니다. 설명란은 50개 단위, 묶음 안의 동일 영상·검색은 재사용합니다. API 키 없음·쿼터 초과·통신/시간 초과·미확정 정보는 곡 등록을 막지 않으며 없는 항목은 빈칸으로 남깁니다. 한도·통신 실패 후 묶음의 추가 외부 조회는 중단합니다. ORIGINAL 곡에는 원곡 조회를 하지 않고 정기 수집·자동 공개 범위도 유지합니다. 원곡 생성·보완·감사와 후보 등록·삭제는 한 트랜잭션이며 동일 요청 재전송 시 외부 조회·원곡 변경을 반복하지 않습니다. 추가 DB 마이그레이션은 없습니다.
- 변경 충돌은 `409 REVIEW_VERSION_CONFLICT`로 전체 취소합니다. 응답 유실 시 요청 ID·본문 전체를 그대로 재전송합니다. 재전송은 `replayed=true`와 기존 `registeredSongs`, `deletedCount=0`(추가 삭제 없음)을 반환하고 정보 재구성·감사 기록도 반복하지 않습니다. `hasMore=true`면 다음 묶음을 별도로 검토합니다. ADMIN·CSRF가 필요하며 서버 고정 사유와 요청별 `BATCH_REGISTER` 감사를 기록합니다. V27을 그대로 사용하며 추가 마이그레이션·권한 변경은 없습니다.
- 입력 오류는 기존 최상위 `code`와 함께 `fieldErrors: [{field, code, message}]`를 반환합니다. `links[0].url`·`aliases[1]`처럼 객체 속성은 점, 배열 위치는 0부터 시작하는 인덱스로 구분합니다. 오류 코드는 `REQUIRED/INVALID_FORMAT/INVALID_SIZE/OUT_OF_RANGE/DUPLICATE/INVALID_VALUE`이며 메시지는 고정 안내 문구입니다. 잘못된 JSON 구조·인증·필드와 무관한 업무 오류는 빈 배열이고, 입력값·비밀번호·토큰은 반환하지 않습니다.
- V24는 YouTube OAuth 상태에 복귀 경로·콜백 선택·콜백 URI를 추가합니다. 운영에서 `SPRING_FLYWAY_ENABLED=false`라면 새 버전 배포 전에 전용 마이그레이터로 적용합니다. 기존 동의 요청은 기본 콜백·복귀 경로로 이어갈 수 있습니다.
- YouTube 계정 연결: CSRF를 포함해 POST `/api/v1/me/youtube/authorizations`에 선택 JSON `{"returnTo":"/library/youtube","callbackClient":"local"}`을 보내고 응답 `authorizationUrl`로 이동합니다. 본문·필드를 생략하면 `/library/youtube`·`default`입니다. 내부 경로만 허용하며 경로·클라이언트는 세션/state에 묶어 5분간 한 번 사용합니다.
  - 로컬: `YOUTUBE_LOCAL_REDIRECT_URI=http://localhost:5173/api/v1/me/youtube/callback/local`을 서버에 설정하고 Google Web OAuth 클라이언트에도 같은 URI를 추가합니다. `/api` 프록시를 통해 기존 로그인 세션·CSRF를 그대로 사용하며 쿠키 속성은 제거하지 않습니다. Google 로그인용 `GOOGLE_LOCAL_REDIRECT_URI`와 별도 설정입니다.
  - 운영: 일반 콜백은 기존 `YOUTUBE_EXPORT_REDIRECT_URI`를 유지합니다. 프론트·API가 같은 origin이면 복귀는 상대 경로이고, 별도 origin이면 서버의 `YOUTUBE_FRONTEND_ORIGIN=https://<프론트 도메인>`에 경로 없는 HTTPS origin을 지정합니다. 요청에서 외부 복귀 URL을 받지 않습니다.
  - 복귀: 303으로 `youtubeResult=success/cancelled/failed`, 취소·실패 때만 `youtubeError`를 전달합니다. 기본 화면은 `/library/youtube`이며 세션 만료·잘못된 state도 각 클라이언트의 기본 화면으로 돌아옵니다. 성공 URL만으로 연결을 확정하지 말고 GET `/api/v1/me/youtube/connection`을 다시 조회합니다. 401이면 로그인 후 다시 연결합니다. 코드·토큰·state·내부 오류는 프론트 URL로 전달하지 않습니다. 오류 코드 전체는 OpenAPI 콜백의 303 응답을 참고합니다.
- YouTube는 같은 Google 계정의 추가 동의를 받습니다. 내보내기에는 UUID `requestId`와 목록 `version`이 필요하며 요청 당시 이름·순서를 새 비공개 목록으로 복사합니다. 이용 불가 곡은 제외합니다. `UNCERTAIN`은 기존 작업을 확인·재시도하고 새 작업으로 반복하지 않습니다. 취소·연결 해제·탈퇴는 외부 목록을 삭제하지 않습니다.

활성 내보내기 복구는 `GET /api/v1/me/youtube-exports/active`를 사용합니다. 본인의 `QUEUED·RUNNING·FAILED·UNCERTAIN` 작업을 기존 `Job` 전체와 같은 `200 {job: {...}}`로 반환하며, 대상이 없으면 `200 {job: null}`입니다. `job` 필드는 항상 존재합니다. `playlistId`·`version`은 접수 당시 원본 목록·버전이며 다른 탭·기기에서도 같은 작업을 조회합니다. `SUCCEEDED·CANCELLED`는 제외하고 조회 자체는 작업을 생성·재시도·취소하거나 YouTube를 호출하지 않습니다. 기능 비활성화·연결 재동의 필요 상태에서도 조회할 수 있습니다. 미인증·세션 만료는 기존 개인 API의 401, 저장소 조회 실패는 503 `EXPORT_LOOKUP_UNAVAILABLE`입니다. 조회 실패를 작업 없음으로 처리하지 않습니다. `EXPORT_ALREADY_ACTIVE`·접수 응답 유실 시 먼저 조회하고 `job.playlistId`의 화면에서 기존 작업을 확인합니다. GET에는 CSRF 토큰이 필요하지 않으며 `Cache-Control: no-store`를 유지합니다.

| 설정 | 기본값·조건 |
| --- | --- |
| 개인 목록 한도 | `FAVORITE_LIMIT=5000`, `PLAYLIST_LIMIT=50`, `PLAYLIST_ITEM_LIMIT=500` |
| 내보내기 | `YOUTUBE_EXPORT_ENABLED`, `YOUTUBE_EXPORT_REDIRECT_URI`, `YOUTUBE_TOKEN_ENCRYPTION_KEY` |
| 자동 커버 공개 | `COVER_AUTO_PUBLICATION_POLICY_ALLOWED`와 관리자 활성화 모두 필요 |
| 급상승 | `TRENDING_ENABLED`와 `TRENDING_POLICY_ALLOWED` 모두 필요 |
| 기념일 후보 | `SPECIAL_EVENT_REVIEW_ENABLED`와 `SPECIAL_EVENT_POLICY_ALLOWED` 모두 필요 |

내보내기는 Data API v3·`youtube.force-ssl` 추가 동의·승인된 콜백과 base64로 인코딩한 별도 32바이트 암호화 키가 필요합니다. 철회가 끝나기 전에 키를 제거하지 않습니다. 공개 사용자 제공과 파생 데이터 기능은 Google 심사·정책 확인을 별도로 진행합니다.

Swagger GET 조회는 `local`·`prod`에서 활성화됩니다. `SPRINGDOC_API_DOCS_ENABLED=false`로 명세·UI를 모두 닫거나 `SPRINGDOC_SWAGGER_UI_ENABLED=false`로 UI만 닫습니다. Swagger 변경 요청도 실제 데이터를 변경합니다.

OpenAPI 파일은 Docker 실행 상태에서 `backend/`의 아래 명령으로 생성합니다.

```sh
./gradlew integrationTest --tests com.stelody.OpenApiIntegrationTest --no-daemon
```

성공 시 `backend/build/openapi/stelody-openapi.json`이 생성됩니다. 전체 check에도 포함되며 CI의 `backend-openapi-<커밋 SHA>` 아티팩트와 해당 커밋을 기준으로 연동합니다.

</details>

<details>
<summary>프론트 연동용 상태값 · 오류 코드</summary>

곡 검색 `GET /api/v1/songs`는 선택 날짜 `startDate`·`endDate`(`YYYY-MM-DD`, 1900~2100)를 지원합니다. 대표 영상 공개일의 한국 날짜 기준으로 양 끝을 포함하며 한쪽만 지정할 수 있습니다. `year`와 함께 지정할 수 없습니다. 기간은 `totalCount`·커서에도 적용하며 기간을 바꾸면 커서를 버립니다. 잘못된 날짜는 400 `INVALID_CATALOG_QUERY`와 `fieldErrors`로 반환합니다(`INVALID_DATE`·`DATE_OUT_OF_RANGE`·`INVALID_DATE_RANGE`·`CONFLICTING_FILTER`).


응답 필드의 가능한 값과 의미는 OpenAPI enum·description에 명시합니다. 여러 명의 참여 여부는 `multipleParticipants`이며 콜라보 표시와 구분합니다. 검색 확인 상태와 사이트 공개 상태도 별도입니다.

### 참여 인원과 기념·협업 표시

- 곡 카드의 `multipleParticipants`는 확정된 멤버·외부 참여자가 2명 이상인지 나타냅니다. 멤버 집계의 `songCounts.multipleParticipants`도 같은 공개 조건으로 계산합니다.
- 곡 검색과 멤버별 곡 목록의 `multipleParticipants=true`는 여러 명이 참여한 곡만 조회합니다. 기본 false는 인원 제한 없음입니다. 이전 검색 파라미터 `collaboration`도 호환용으로 같은 조건으로 해석합니다. 둘을 함께 보내면 값이 같아야 하며, 다르면 400 `INVALID_CATALOG_QUERY`와 양쪽 `fieldErrors`의 `CONFLICTING_FILTER`를 반환합니다.
- 멤버 집계의 이전 이름 `songCounts.collaborations`는 기존 클라이언트 호환을 위해 같은 숫자를 함께 반환하고 OpenAPI에서 deprecated로 표시합니다. 새 연동에는 `multipleParticipants`를 사용합니다. 곡 카드의 `collaboration` 필드는 제거하여 잘못된 콜라보 배지의 근거로 제공하지 않습니다.
- `multipleParticipants`로 ‘콜라보’ 배지를 만들지 않습니다. 게임·IP·브랜드 협업의 표시는 관리자가 확인한 `isSpecialEvent`·`specialEventLabel`을 사용합니다. 생일·데뷔·주년·신의상 등도 같은 특별 목적 필드에 포함되므로 `isSpecialEvent=true`만으로 게임 콜라보를 판정하지 않습니다. 자유 입력 문구를 그대로 표시하며 고정 분류나 문구 기반 자동 추론을 추가하지 않습니다.
- 특별 목적 문구는 해당 스텔라이브 곡에만 귀속됩니다. 같은 원곡을 공유하는 다른 커버로 전파하지 않습니다. 기존 커서의 참여 인원 조건은 유지되며 필터를 변경하면 커서를 버리고 첫 페이지부터 조회합니다.

| 필드 | 값·의미 |
| --- | --- |
| 곡 `type` | `ORIGINAL` 오리지널, `COVER` 커버 |
| 멤버 `activityStatus` | `ACTIVE` 활동 중, `GRADUATED` 졸업; 외부 참여자는 null |
| 곡 `visibility`·관리자 곡 `status` | `DRAFT` 초안, `PUBLISHED` 공개 설정, `HIDDEN` 숨김; 실제 공개 API 조건은 별도로 적용 |
| 검색 `visibility`·`searchVisibility` | `UNCHECKED` 미확인, `NORMAL` 정상 검색, `DIFFICULT` 검색 어려움 |
| YouTube 연결 `status` | `DISABLED` 기능 비활성·연결 없음, `DISCONNECTED` 미연결, `CONNECTED` 연결됨, `RECONNECT_REQUIRED` 추가 동의 필요, `REVOKING` 철회 중 |

내보내기 상태별 동작은 다음과 같습니다. retry 접수 성공은 작업 완료를 의미하지 않으며, 작업 상태를 다시 조회합니다.

| 내보내기 `status` | 의미 | retry | cancel |
| --- | --- | --- | --- |
| `QUEUED` | 대기 | 현재 상태 유지 | `CANCELLED` |
| `RUNNING` | 처리 중 | 현재 상태 유지 | `CANCELLED` |
| `SUCCEEDED` | 완료; 건너뛴 곡 포함 가능 | 현재 상태 유지 | 현재 상태 유지 |
| `FAILED` | 실패 | 연결 확인 후 `QUEUED` | `CANCELLED` |
| `UNCERTAIN` | 외부 쓰기 결과 불확실 | 연결 확인 후 `QUEUED`, 원격 결과 대조 후 재개 | `CANCELLED` |
| `CANCELLED` | 취소 | 409 `EXPORT_NOT_RETRYABLE` | 현재 상태 유지 |

retry는 기능 활성화가 필요합니다. `UNCERTAIN`에서는 새 요청으로 반복하지 않고 같은 작업을 확인·재시도합니다. 확인되지 않으면 다시 `UNCERTAIN`에 머무를 수 있습니다. 취소해도 진행 중인 외부 요청은 반영될 수 있고 이미 만든 YouTube 목록·곡은 삭제하지 않습니다. `totalCount = copiedCount + skippedCount + remainingCount`이며 불확실 상태의 남은 수는 미반영 수로 단정하지 않습니다.

`Job.errorCode=YOUTUBE_TOKEN_UNAVAILABLE`은 토큰 갱신 중 외부 오류입니다. 기존 연결·작업을 유지하므로 잠시 후 같은 작업의 retry를 호출합니다. 새 내보내기를 접수하지 않습니다. `invalid_grant`로 연결이 `RECONNECT_REQUIRED`가 되면 추가 동의가 필요합니다.

HTTP 오류는 `ApiProblem.code`로 구분합니다. `fieldErrors[].code`는 입력칸 오류, `Job.errorCode`는 비동기 작업 사유입니다. OpenAPI의 오류 응답에 `x-error-codes`와 처리 의미를 제공합니다. 다음은 주요 안내 코드이며, 새 코드는 일반 오류 안내와 `traceId`로 처리합니다.

| HTTP·코드 | 프론트 처리 |
| --- | --- |
| 401 `AUTHENTICATION_REQUIRED`·`SESSION_EXPIRED` | 다시 로그인 |
| 403 `ACCESS_DENIED` | 권한·새 CSRF 확인 |
| 400 `INVALID_CATALOG_QUERY`·`INVALID_FAVORITE_QUERY`·`INVALID_PLAYLIST_REQUEST`·`INVALID_CATALOG_REQUEST`·`INVALID_EXPORT_REQUEST` | 입력·커서 확인; `fieldErrors`가 있으면 입력칸에 표시 |
| 409 `PLAYLIST_SONG_ALREADY_EXISTS` | 이미 담긴 곡 표시; 즐겨찾기 중복 저장은 204 성공 |
| 409 `FAVORITE_LIMIT_REACHED`·`PLAYLIST_LIMIT_REACHED`·`PLAYLIST_ITEM_LIMIT_REACHED` | 저장·생성 한도 안내 |
| 409 `PLAYLIST_CHANGED`·`CATALOG_VERSION_CONFLICT` | 최신 자료를 읽고 편집 내용 확인 |
| 409 `CATALOG_DATA_CONFLICT` | 중복·연결 조건 확인 |
| 409 `VIEW_PUBLICATION_CHANGED` | 조회수순 목록의 이전 커서를 버리고 첫 페이지부터 조회 |
| 409 `YOUTUBE_CONNECTION_REQUIRED`·`YOUTUBE_REVOCATION_PENDING` | 추가 동의 또는 철회 완료 대기 |
| 409 `EXPORT_REQUEST_REUSED`·`EXPORT_ALREADY_ACTIVE`·`NO_EXPORTABLE_SONGS` | 요청 ID 재사용 조건·기존 작업·공개 곡 확인 |
| 404 `CATALOG_NOT_FOUND`·`PLAYLIST_NOT_FOUND`·`EXPORT_NOT_FOUND` | 접근 가능한 자료 없음 안내; 내보내기는 연결 해제·보관 기한 경과로도 삭제 가능 |
| 503 `YOUTUBE_EXPORT_DISABLED`·`YOUTUBE_OAUTH_UNAVAILABLE` | 기능 비활성·설정 준비 중 안내 |

</details>


<details>
<summary>DB · Render 배포 · 관리자 지정</summary>

PostgreSQL 17을 사용합니다. Supabase는 프로젝트 Connect 화면의 **Session pooler 전체 호스트·5432**와 `sslmode=verify-full`을 사용합니다. 세션 잠금이 필요한 수집·내보내기·마이그레이션에는 Transaction pooler를 사용하지 않습니다.

신규 빈 DB는 [Supabase 역할 SQL](../infra/deploy/prepare-supabase-roles.sql), 직접 운영하는 DB는 [일반 역할 SQL](../infra/deploy/prepare-roles.sql)로 준비합니다. 기존 역할·스키마를 덮어쓰지 않습니다. 비밀번호는 대화형 psql의 `\password`로 따로 설정합니다.

| 역할 | 용도 |
| --- | --- |
| `stelody_migrator` | app/session 스키마 소유·Flyway |
| `stelody_app` | 웹·세션 |
| `stelody_collector` | 수집 전용; 회원·개인 목록·세션·OAuth 접근 없음 |
| `stelody_operator` | 관리자 지정 전용 |

Supabase 접속 이름에는 프로젝트 접미사가 붙지만 `DB_RUNTIME_ROLE`은 실제 역할명입니다. 웹 `DB_URL/USERNAME/PASSWORD`와 `DB_MIGRATION_USERNAME/PASSWORD`를 분리합니다. 마이그레이션 후 웹에서 해당 비밀번호를 제거하면 `SPRING_FLYWAY_ENABLED=false`를 적용하고 다음 스키마 변경 전에 다시 마이그레이션합니다.

수집·운영 기능 사용 전 스키마 소유자로 [infra/sql](../infra/sql/)의 collector·discovery·statistics-collector·collection-operations·collection-rule·cover-publication·admin-operator 권한 SQL을 적용합니다. 실제 역할명을 확인하고 `official-channels.sql`의 채널·멤버 관계도 검토합니다. 신규 DB의 카탈로그는 별도로 등록하며 로컬 후보 ID·회원·세션을 운영에 그대로 복사하지 않습니다.

| Render 항목 | 값 |
| --- | --- |
| Source / Branch / Language | Stelody GitHub / `main` / Docker |
| Region / 프로필 / 포트 | Singapore / `prod,google,render` / `10000` |
| Root Directory / Build Context | 빈칸 / 저장소 루트 `.` |
| Dockerfile / Health Check | `backend/Dockerfile.render` / `/actuator/health` |

[Backend CI](../.github/workflows/backend-ci.yml)가 테스트를 통과한 같은 실행의 JAR로 `linux/amd64` 운영 이미지를 만들고 `ghcr.io/minhyukji1226/stelody-backend:<전체 커밋 SHA>`에 게시합니다. Render는 `RENDER_GIT_COMMIT`에 해당하는 이미지를 가져와 실행하므로 Gradle·Maven 다운로드와 소스 빌드를 수행하지 않습니다. PR에서는 이미지 생성·가져오기·실행 구성을 검증하며 게시하지 않습니다.

Render Auto-Deploy는 **After CI Checks Pass**로 설정합니다. main의 이미지 게시까지 성공해야 CI가 통과합니다. GHCR 패키지는 첫 게시 시 비공개이므로 최초 1회 실행 이미지의 공개 설정을 확인한 뒤 배포합니다. 이미지는 검증된 JAR·시작 스크립트만 포함하고 실제 운영 설정은 Render Environment에서 주입합니다. `latest` 대신 커밋 태그를 사용하고, 롤백에 필요한 이전 이미지도 보관합니다. CI의 Maven 다운로드나 GHCR 게시·가져오기 실패는 여전히 CI 또는 배포 실패로 표시됩니다.

로컬에서는 `./backend/gradlew -p backend bootJar` 후 아래 명령으로 운영 이미지를 검증할 수 있습니다. Render용 Dockerfile을 직접 빌드할 때는 `RENDER_GIT_COMMIT`이 필요합니다.

```sh
docker build --platform linux/amd64 -f backend/Dockerfile.release -t stelody-render:local .
docker build --platform linux/amd64 -f backend/Dockerfile.render \
  --build-arg STELODY_IMAGE_REPOSITORY=stelody-render \
  --build-arg RENDER_GIT_COMMIT=local -t stelody-render-pull:local .
```

[render.env.example](../infra/deploy/render.env.example)을 참고해 실제 값을 Render Environment에 넣습니다. `DB_CA_CERTIFICATE_BASE64`는 공개 CA이며 시작 스크립트가 `/tmp/stelody-db-ca.crt`로 준비합니다. 웹·수집 JDBC URL의 `sslrootcert`도 같은 경로여야 합니다. 예시로 기존 운영 설정을 통째로 덮어쓰지 않습니다.

Google에는 실제 HTTPS 주소의 `/api/v1/auth/callback/google`, `/api/v1/me/youtube/callback`을 등록합니다. 명시한 `GOOGLE_REDIRECT_URI`, `YOUTUBE_EXPORT_REDIRECT_URI`와 일치해야 합니다. render 기본값은 `RENDER_EXTERNAL_URL`을 사용합니다.

CI 성공 후 main을 배포하도록 Auto-Deploy를 설정하고 **Live 상태·커밋**을 확인합니다. 롤백 전에는 이전 앱과 새 스키마의 호환성을 확인합니다. 배포 후 health·공개 API·인증/CSRF·시험 계정의 개인 기능·수집·백업 결과를 확인합니다.

관리자 지정은 별도 `ADMIN_DB_URL/USERNAME/PASSWORD`와 기존 ACTIVE 회원 UUID를 사용합니다. 저장소 루트에서:

```sh
java -jar backend/build/libs/stelody-0.0.1-SNAPSHOT.jar --admin-account \
  --user-id=<회원 UUID> --role=ADMIN --reason='운영 담당자 확인'
```

`--role=USER`로 철회합니다. 웹·수집 자격 증명을 대신 쓰지 않습니다. Linux 직접 배포 설정은 [infra/deploy](../infra/deploy/)에 있으며 비공개 설정 파일을 컨테이너 UID 10001이 읽도록 준비하고 웹 포트를 HTTPS 프록시에 연결합니다.

</details>

<details>
<summary>정기 수집 설정 · 수동 실행</summary>

| 환경변수 | 설정 |
| --- | --- |
| `COLLECTOR_ENABLED` / `COLLECTOR_SCHEDULED_ENABLED` | 둘 다 true |
| `COLLECTOR_DB_URL/USERNAME/PASSWORD` | 수집 전용 계정·TLS URL |
| `YOUTUBE_API_KEY` / `DISCOVERY_ENABLED` | 수집 키 / 신규 탐색 활성화 |
| `COLLECTION_RETRY_ENABLED` | 관리자 재시도 활성화 |
| `DISCOVERY_CLASSIFICATION_ALLOWED` | 제목 분류 허용; 자동 공개와 별도 |
| `SELF_KEEP_ALIVE_ENABLED` | render에서 공개 health 주소에 5분 간격 자체 요청 |

웹 프로필에 collector를 추가하지 않습니다. 기동 1분 후 현재 시간 슬롯·당일 탐색을 보충하고 미완료·실패 작업은 15분 뒤 다시 확인합니다. 완료되면 추가 확인을 예약하지 않습니다. 관리자 재시도는 다음 정각 또는 보충 실행에서 처리하며 과거 조회수는 만들어 채우지 않습니다.

정기 수집은 Render의 Spring에서 실행합니다. GitHub Actions의 수집 workflow는 제거했으며 수동 수집·과거 탐색은 아래 로컬 실행 명령을 사용합니다.

**YouTube 업로드 알림:** `COLLECTOR_ENABLED=true`·`DISCOVERY_ENABLED=true`인 웹 서버에 아래 설정을 추가하면 공식 Hub의 업로드·제목/설명 변경 알림으로 해당 영상을 확인합니다. 기존 채널 목록의 수집 활성 `GROUP/MEMBER`만 구독하며 기존 분류·중복 검사와 자동 공개 설정/정책 게이트를 재사용합니다. 이 기능은 자동 공개 설정을 켜지 않습니다. 매일 00시 탐색과 매시간 조회수 갱신을 유지하고, 기존 프론트 API·화면 재조회 방식을 사용합니다.

| 환경변수 | 값 |
| --- | --- |
| `DISCOVERY_PUSH_ENABLED` | 기본 `false`, 운영 활성화 시 `true` |
| `DISCOVERY_PUSH_CALLBACK_ORIGIN` | 외부에서 접근 가능한 HTTPS 백엔드 origin. 생략 시 `RENDER_EXTERNAL_URL`. 경로·쿼리·사용자 정보 없이 지정 |
| `DISCOVERY_PUSH_SECRET` | 비공개 랜덤 문자열 32~128 UTF-8 바이트. 예: `openssl rand -hex 32`로 생성하여 서버 환경에만 저장 |

기동 1분 후 전용 스케줄러에서 구독을 요청하고 5분마다 대상·갱신 필요 여부를 확인합니다. 요청 lease는 24시간이며 Hub가 확인한 실제 lease의 만료 1시간 전부터 갱신합니다. 확인을 기다리는 요청은 5분 후 재시도하고, Hub 접수 응답만으로 구독 완료를 판단하지 않습니다. 구독 상태는 메모리에 두므로 재시작 후 재구독합니다. 단일 웹 인스턴스 기준이며 수평 확장 시 구독 확인 상태를 공유하는 보완이 필요합니다. 수집 비활성·외부 채널은 새 구독을 중지하고 콜백을 거절하며 기존 Hub 구독은 lease 만료로 종료됩니다. 새 DB 테이블·마이그레이션·백업 변경은 없습니다.

콜백 `GET/POST /api/v1/integrations/youtube/push/{channel}/{token}`은 Hub 전용이며 프론트 OpenAPI에는 노출하지 않습니다. 채널별 capability와 HMAC 비밀키를 파생해 검증하고 브라우저 세션·CSRF를 사용하지 않습니다. GET은 요청 중인 구독·허용 채널·topic·lease를 확인한 뒤 challenge를 `200 text/plain`으로 반환합니다. POST는 SHA-1/SHA-256 서명, 최대 256KiB·50개 entry의 Atom XML 및 채널 ID를 검증합니다. DTD·외부 엔티티는 금지하며 알림의 제목·링크를 그대로 저장하거나 요청 URL을 따라가지 않습니다.

콜백은 기존 전용 수집 역할·공유 잠금을 사용해 YouTube API에서 최신 정보를 확인하고 저장한 뒤 `204`를 반환합니다. 이미 등록·무시한 영상과 이미 관측한 같은/과거 알림은 반복 저장하지 않습니다. 잘못된 XML은 400, 서명 오류는 403, 비활성/허용되지 않은 채널·구독 확인은 404, 크기 초과는 413입니다. 수집 잠금 충돌·API 지연·쿼터/저장소 실패는 `503 Retry-After: 60`으로 Hub의 제한된 재전송을 유도합니다. 한 알림에 여러 영상이 있으면 먼저 확인된 영상은 저장하고 아직 확인되지 않은 영상은 재전송 때 다시 조회합니다. 외부 조회는 요청당 최대 20초이며 성공을 먼저 응답하고 메모리 작업으로 넘기지 않습니다. Hub 재전송에도 한계가 있으므로 외부 접속 장애·서버 휴면 등에 대한 누락 보완은 매일 탐색이 담당합니다. 즉시 수신 시간을 보장하지 않습니다.

푸시 처리는 업로드 목록의 커서·backfill·일일 탐색 완료 시점을 변경하지 않습니다. 별도 수집 작업 행을 만들지 않아 기존 관리자 작업 집계에는 알림별 실행을 추가하지 않으며, 구독/처리 실패는 비밀값·원문을 제외한 고정 코드 로그로 확인합니다. 원본 XML·구독 비밀키·capability는 DB에 저장하거나 로그로 출력하지 않습니다. 운영 적용은 백엔드 배포 후 위 환경 설정과 실제 Hub 구독 확인이 필요하며 Google 로그인/OAuth 콜백 설정은 변경하지 않습니다.

자체 요청은 실행 중인 서버에서만 동작하며 절전 방지를 보장하지 않습니다. 실제 수집은 로그와 `/api/v1/admin/collection-status`에서 확인합니다. 수집 환경변수와 jar를 준비한 뒤 수동으로 실행할 수 있습니다.

```sh
java -jar backend/build/libs/stelody-0.0.1-SNAPSHOT.jar --collector --discover
```

조회수만 갱신하면 `--discover`를 생략합니다. 과거 탐색은 `--collector --discover --backfill --channel=<채널 UUID> --max-pages=1`을 사용합니다.

</details>

<details>
<summary>암호화 백업 · 독립 탈퇴 기록 · 복구</summary>

백업·복구는 V19~V28을 지원하며 멤버 날짜·유닛·링크·표시 순서도 보존합니다. 새 마이그레이션은 백업·복구 검증과 지원 버전 갱신을 함께 완료해야 합니다.

백업 준비 시 스키마 소유자·지원 버전을 검증한 뒤, 연결된 동일 YouTube 영상에 같거나 더 최신인 수집 정보가 있는 `REGISTERED` 후보의 중복 `source_*` 4개 필드만 비웁니다. 등록 상태·영상 연결·관측 시각·감사·요청 재전송 이력은 보존하고 버전만 증가시킵니다. 대기·제외·보류 후보와 연결 영상의 정보는 유지합니다. 이 정리는 운영 DB에서 실행되며 백업 실패 시에도 이미 정리된 중복 정보는 되돌리지 않습니다. 남아 있는 실제 수집 정보는 계속 백업 만료 계산에 포함합니다.

백업은 카탈로그·회원·즐겨찾기·개인 목록을 보존합니다. 세션·30일 로그인 유지 자격·재인증·탈퇴 결과 조회 권한/기록·YouTube 토큰/동의/내보내기·조회수 표본·수집 작업은 제외합니다. 최대 유효 기간은 7일이며 원본 자료 기한이 먼저 오면 더 짧아집니다. 복구 키와 별도 탈퇴 기록이 필요합니다.

**백업 저장소:** 전용 Private B2 버킷, `stelody/backups/` 범위의 읽기·쓰기·목록·삭제 키를 사용합니다. Object Lock은 끕니다. 업로드 1일 후 숨김·숨김 1일 후 삭제·미완료 대용량 업로드 1일 후 취소를 설정합니다. 일일 처리로 실제 삭제가 늦어질 수 있습니다. 작업은 만료된 모든 버전을 VersionId로 삭제하며 업로드에는 원본 자료 기한이 5일 이상 남아야 합니다. 덤프·암호문 상한은 각각 128MiB입니다.

GitHub environment `production-backup`의 배포 브랜치를 main으로 제한합니다. 무인 실행이 필요하면 매 실행 승인 대기로 멈추지 않도록 보호 설정을 확인합니다.

| 종류 | 이름 |
| --- | --- |
| Secrets 6개 | `BACKUP_DB_HOST`, `BACKUP_DB_USERNAME`, `BACKUP_DB_PASSWORD`, `BACKUP_DB_CA_CERTIFICATE_BASE64`, `BACKUP_B2_ACCESS_KEY_ID`, `BACKUP_B2_SECRET_ACCESS_KEY` |
| Environment Variables | `BACKUP_B2_ENDPOINT`, `BACKUP_B2_BUCKET`, `BACKUP_AGE_RECIPIENT` |
| Repository Variable | `BACKUP_ENABLED=true` |

[백업 workflow](../.github/workflows/database-backup.yml)는 만료 정리 후 최근 백업을 검사합니다. 수동 기본값은 정리만 하며 `backup=true`로 새 백업을 만듭니다. 업로드 후 같은 VersionId의 암호문·checksum·metadata를 다시 읽어 검증합니다. 원본 자료 기한이 5일 미만 남으면 업로드 전에 차단하고 `BACKUP_SOURCE_EXPIRY_TOO_SOON`으로 안내합니다. 저장소·연결의 알 수 없는 오류 원문은 로그에 출력하지 않습니다. 실패 알림은 GitHub Actions 이메일 설정을 사용합니다. 개인키는 GitHub·Render에 주지 않고 서버 밖에도 보관합니다.

**탈퇴 기록:** 백업과 다른 Private 버킷·전용 키를 사용합니다. `stelody/withdrawals/records/`만 8일 후 숨김·숨김 1일 후 삭제를 설정하며 `coverage.json`에는 삭제 규칙을 적용하지 않습니다. 겹치는 규칙을 만들지 않습니다.

Render에 `WITHDRAWAL_JOURNAL_ENABLED=true`, `WITHDRAWAL_B2_ENDPOINT/BUCKET/ACCESS_KEY_ID/SECRET_ACCESS_KEY`, `WITHDRAWAL_AGE_RECIPIENT`, `WITHDRAWAL_LEDGER_ID`를 설정합니다. 키 범위는 `stelody/withdrawals/`이며 버킷 상태·규칙 읽기와 객체 읽기·쓰기가 필요합니다. Docker의 age 경로는 `/usr/bin/age`입니다.

모든 웹 인스턴스가 기록을 켠 릴리스이고 이전 앱·직접 삭제 경로가 중지됐는지 확인한 뒤, 같은 설정을 주입한 비공개 환경에서 **처음 한 번** 시작 기준을 만듭니다.

```sh
python3 infra/backup/withdrawals.py initialize \
  --ledger-id <Render와 같은 UUID> --confirm-all-writers-recording
```

시험 계정 탈퇴를 검증하고 시작 기준 **이후** 새 백업을 만듭니다. 암호화 기록의 저장·다시 읽기에 실패하면 DB 삭제를 취소합니다. 기록 후 DB 확정이 실패해도 기록은 보존하며 복구 삭제 대상에 포함합니다. 기록을 끄거나 직접 삭제해 누락 가능성이 생기면 연속 기록을 확인했다고 선언하지 않습니다.

**복구:** PostgreSQL 17 클라이언트·Python 3.11+·age와 [Python 의존성](../infra/backup/requirements.txt)이 필요합니다. DB 접속은 TLS를 검증하고 비공개 환경·600 권한의 PGPASSFILE로 제공합니다. B2 읽기 키와 `BACKUP_AGE_IDENTITY_FILE`은 복구 환경에만 준비합니다.

1. 웹·수집·내보내기·직접 DB 변경을 모두 멈추고 유효한 백업을 다운로드·복호화합니다.

```sh
python3 infra/backup/offsite.py download --key '<stelody/backups/...age>' --directory /비공개/encrypted
python3 infra/backup/offsite.py unseal --source /비공개/encrypted/<파일>.age --directory /비공개/plain
```

2. 중지 확인 후 30분 안에 탈퇴 기록을 대조합니다. 넘기면 중지 상태를 다시 확인합니다. 연속 기록을 입증할 수 없거나 백업이 시작 기준 이전이면 공개 재개를 진행하지 않습니다.

```sh
python3 infra/backup/withdrawals.py reconcile \
  --backup /비공개/plain/<백업-폴더> \
  --services-stopped-at <실제-중지-확인-시각과-시간대> \
  --confirm-continuous-recording --output /비공개/withdrawals.json
```

3. 원래 역할명·ACL을 준비한 **이름이 다른 빈 DB**에 복원합니다. PGDATABASE도 새 DB로 설정합니다.

```sh
python3 infra/backup/database.py restore \
  --backup /비공개/plain/<백업-폴더> --target stelody_restore \
  --withdrawals /비공개/withdrawals.json
```

4. 탈퇴 대상·개인 자료 잔여 0, 세션·토큰 제외, 카탈로그·개인 목록·권한과 Flyway/JPA를 검증합니다. 재로그인·YouTube 재연결·수집 성공을 확인한 뒤 접속 경로와 정기 실행을 복구합니다.

복구는 조회수 포인터를 초기화하고 자동 공개를 끕니다. `readyForPublicTraffic=false`는 후속 검증 전 상태입니다. 기록의 강제 삭제·상충·불명확한 숨김 등 누락 가능성을 확인해야 하며 대조 도구만으로 연속 기록을 증명하지 않습니다. 스키마 변경 시 백업 제외·정리 SQL과 복구 시험도 갱신합니다.

</details>

실제 비밀번호·API 키·OAuth 토큰·복구 개인키는 Git에 올리지 않습니다. 개발·커밋 규칙은 [AGENTS.md](../AGENTS.md)를 따릅니다.
