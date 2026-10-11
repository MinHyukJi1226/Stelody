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
- GET `/api/v1/me/playlists?songId=<UUID>`는 각 목록의 `containsSong`으로 곡 저장 여부를 반환합니다. 이용 불가 곡도 이미 저장되어 있으면 `true`, 미저장·존재하지 않는 UUID는 `false`입니다. `songId` 생략 및 생성·상세·변경 응답은 `null`입니다. 목록 정렬·커서·개수는 그대로이며, 추가 요청의 `version`·중복 검증은 계속 사용합니다.
- 로그인 `returnTo`는 `/`로 시작하는 내부 UI 경로만 허용합니다. 최대 2,048자이며 외부 주소·경로 우회·`/api`·`/actuator`는 거절합니다. 같은 브라우저에서 5분 안에 완료해야 합니다. 생략하면 `/api/v1/me`로 이동합니다. `loginResult`는 안내용이며 실제 로그인은 `/api/v1/me`로 확인합니다.
- 세션은 idle 30분·로그인 후 최대 12시간입니다. 탈퇴는 POST `/api/v1/me/reauthentications`로 같은 계정을 재인증한 뒤 새 CSRF로 5분 내 DELETE `/api/v1/me`를 호출합니다. 철회·탈퇴 기록 저장 실패 시 계정은 유지됩니다.
- 조회수는 대표 영상 하나의 실제 관측값이며 미관측 날짜는 null입니다. 커버는 공식 커버 영상, 오리지널은 공식 MV 또는 MV가 없을 때 공식 음원을 대표로 사용합니다. 영상 간 조회수를 합산하지 않습니다.
- 곡 검색·멤버별 곡 목록의 `totalCount`는 검색어·필터·공개 조건에 일치하는 전체 고유 곡 수입니다. 페이지 크기·정렬·커서와 무관하며 빈 검색·결과 없음은 `0`입니다. `items.length`는 현재 페이지 크기이고 무한 스크롤은 기존 `nextCursor`·`hasNext`를 사용합니다. 요청 사이 카탈로그가 바뀌면 전체 개수도 달라질 수 있습니다.
- GET `/api/v1/songs/years`는 일반 탐색과 같은 공개 조건을 만족하는 곡의 검색 가능 연도(1900~2100)를 `{"years": [2026, 2025]}`로 제공합니다. 대표 영상 공개일의 한국 시간 기준이며 중복 없이 내림차순, 곡이 없으면 빈 배열입니다. 현재 검색어·유형·멤버 필터와 무관한 전체 선택지로 사용합니다.
- 공개 멤버 목록·상세와 관리자 멤버 등록·수정·상세의 `unitName`은 기수와 별도인 유닛명, `chzzkUrl`·`xUrl`은 HTTPS 링크입니다. 미등록이면 `null`, YouTube 채널이 없으면 `channels: []`입니다. 관리자 PUT에서 새 필드를 생략하거나 null·빈 문자열로 보내면 기존 값을 지우므로 유지할 값은 함께 전달합니다.
- V20에는 운영자가 제공한 기존 11명 프로필 초기값이 포함됩니다. 카탈로그의 멤버 ID가 일치할 때만 적용하며 멤버를 새로 생성하지 않습니다. 이후 변경은 관리자 API를 사용합니다.
- GET `/api/v1/admin/songs`는 기존 `id/name/version/status`에 `participants`, `discoveredAt`, `missingFields`, `informationComplete`를 제공합니다. 참여자는 멤버·외부 아티스트 순서이며 미확정 항목도 `confirmed=false`로 표시합니다. 발견일은 연결된 영상 후보의 최초 발견 시각이고 기록이 없으면 `null`입니다. 자동 등록 목록의 누락 코드 6개에 `members/representativeVideo`를 더하며, 선택 별칭(`aliases`)만 없으면 완성입니다. 공개 설정(`status`)과 완성도는 독립적이며 전체 곡에 같은 기준을 적용합니다.
- GET `/api/v1/admin/inbox`는 신규 검토(`PENDING+REVIEW`), 자동 등록 곡의 정보 보완, 기념일 `PENDING` 후보, 최근 24시간 미해결 수집 실패 작업 수를 반환합니다. 보완 완료·검토 처리·해당 원본 작업 재시도 성공 시 집계에서 제외합니다. 기념일 후보는 만료·비활성 근거도 재검토 대상으로 포함합니다. 실패 목록은 `/api/v1/admin/collection-failures`에 응답의 `failureWindowStart/checkedAt`을 `from/to`로 전달하면 같은 기간으로 조회합니다. 집계 단위·누락 조건은 OpenAPI에 명시하며 기존 설정·수집 현황 API를 함께 사용합니다.
- 입력 오류는 기존 최상위 `code`와 함께 `fieldErrors: [{field, code, message}]`를 반환합니다. `links[0].url`·`aliases[1]`처럼 객체 속성은 점, 배열 위치는 0부터 시작하는 인덱스로 구분합니다. 오류 코드는 `REQUIRED/INVALID_FORMAT/INVALID_SIZE/OUT_OF_RANGE/DUPLICATE/INVALID_VALUE`이며 메시지는 고정 안내 문구입니다. 잘못된 JSON 구조·인증·필드와 무관한 업무 오류는 빈 배열이고, 입력값·비밀번호·토큰은 반환하지 않습니다.
- YouTube는 같은 Google 계정의 추가 동의를 받습니다. 내보내기에는 UUID `requestId`와 목록 `version`이 필요하며 요청 당시 이름·순서를 새 비공개 목록으로 복사합니다. 이용 불가 곡은 제외합니다. `UNCERTAIN`은 기존 작업을 확인·재시도하고 새 작업으로 반복하지 않습니다. 취소·연결 해제·탈퇴는 외부 목록을 삭제하지 않습니다.

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

Render 수집 성공 후 GitHub Variable `COLLECTOR_SCHEDULE_OWNER=render`로 이중 예약을 막습니다. GitHub 수집 Secrets는 수동 실행용으로 유지합니다. 복귀할 때는 Render의 `COLLECTOR_SCHEDULED_ENABLED=false`를 먼저 적용한 뒤 owner를 제거합니다. GitHub 기존 예약은 조회수 매시간 :17·탐색 2시간 간격입니다.

자체 요청은 실행 중인 서버에서만 동작하며 절전 방지를 보장하지 않습니다. 실제 수집은 로그와 `/api/v1/admin/collection-status`에서 확인합니다. 수집 환경변수와 jar를 준비한 뒤 수동으로 실행할 수 있습니다.

```sh
java -jar backend/build/libs/stelody-0.0.1-SNAPSHOT.jar --collector --discover
```

조회수만 갱신하면 `--discover`를 생략합니다. 과거 탐색은 `--collector --discover --backfill --channel=<채널 UUID> --max-pages=1`을 사용합니다.

</details>

<details>
<summary>암호화 백업 · 독립 탈퇴 기록 · 복구</summary>

백업은 카탈로그·회원·즐겨찾기·개인 목록을 보존합니다. 세션·재인증·YouTube 토큰/동의/내보내기·조회수 표본·수집 작업은 제외합니다. 최대 유효 기간은 7일이며 원본 자료 기한이 먼저 오면 더 짧아집니다. 복구 키와 별도 탈퇴 기록이 필요합니다.

**백업 저장소:** 전용 Private B2 버킷, `stelody/backups/` 범위의 읽기·쓰기·목록·삭제 키를 사용합니다. Object Lock은 끕니다. 업로드 1일 후 숨김·숨김 1일 후 삭제·미완료 대용량 업로드 1일 후 취소를 설정합니다. 일일 처리로 실제 삭제가 늦어질 수 있습니다. 작업은 만료된 모든 버전을 VersionId로 삭제하며 업로드에는 원본 자료 기한이 5일 이상 남아야 합니다. 덤프·암호문 상한은 각각 128MiB입니다.

GitHub environment `production-backup`의 배포 브랜치를 main으로 제한합니다. 무인 실행이 필요하면 매 실행 승인 대기로 멈추지 않도록 보호 설정을 확인합니다.

| 종류 | 이름 |
| --- | --- |
| Secrets 6개 | `BACKUP_DB_HOST`, `BACKUP_DB_USERNAME`, `BACKUP_DB_PASSWORD`, `BACKUP_DB_CA_CERTIFICATE_BASE64`, `BACKUP_B2_ACCESS_KEY_ID`, `BACKUP_B2_SECRET_ACCESS_KEY` |
| Environment Variables | `BACKUP_B2_ENDPOINT`, `BACKUP_B2_BUCKET`, `BACKUP_AGE_RECIPIENT` |
| Repository Variable | `BACKUP_ENABLED=true` |

[백업 workflow](../.github/workflows/database-backup.yml)는 만료 정리 후 최근 백업을 검사합니다. 수동 기본값은 정리만 하며 `backup=true`로 새 백업을 만듭니다. 업로드 후 같은 VersionId의 암호문·checksum·metadata를 다시 읽어 검증합니다. 실패 알림은 GitHub Actions 이메일 설정을 사용합니다. 개인키는 GitHub·Render에 주지 않고 서버 밖에도 보관합니다.

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
