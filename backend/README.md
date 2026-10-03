# Stelody 백엔드

스텔라이브 노래 정보를 모아 검색하고 개인 목록에 저장하는 서비스입니다.

## 백엔드 개발 환경

Java 21, Gradle Wrapper 8.14.3, Spring Boot 4.1.1을 사용합니다. Docker Compose가 필요합니다.

아래 명령은 `backend/` 디렉토리에서 실행합니다.

```sh
docker compose -f ../infra/compose.yml up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

- 서버: http://localhost:8080
- 상태 확인: http://localhost:8080/actuator/health
- 로컬 메일함: http://localhost:8025 (Google 로그인에는 사용하지 않음)
- Google 가입·로그인, CSRF 토큰, 내 정보 조회, 로그아웃, 공개 곡·멤버 조회, 즐겨찾기와 개인 플레이리스트를 제공합니다.

로컬 PostgreSQL의 기본 계정은 `stelody`, 비밀번호는 `local-only-password`입니다. 로컬 포트는 루프백에만 바인딩합니다. 운영에서는 [환경변수 예시](../.env.example)의 환경변수를 별도 주입하세요. Spring Boot는 루트 `.env`를 자동으로 읽지 않습니다. 운영 DB URL은 TLS 인증서 검증을 사용하고 실행 계정과 마이그레이션 계정을 분리해야 합니다.

### DB 역할과 권한

DB 관리자가 로그인 역할 두 개를 먼저 준비합니다. 마이그레이션 역할은 대상 DB의 `CONNECT`, `CREATE` 권한으로 `app`·`session` 스키마를 생성하고 소유합니다. 실행 역할은 `CONNECT`만 사전 부여하며 슈퍼유저·마이그레이션 역할 멤버십을 부여하지 않습니다.

Flyway V2가 실행 역할에 `app`·`session`의 `USAGE`와 세션 테이블 두 개의 `SELECT`, `INSERT`, `UPDATE`, `DELETE`를 부여합니다. 테이블 생성·삭제, Flyway 이력 테이블 접근, 미래 테이블에 대한 일괄 권한은 부여하지 않습니다. 업무 테이블을 추가할 때 필요한 권한을 해당 마이그레이션에서 명시합니다. V1 적용 DB에도 V2가 순차 적용되며 기존 V1은 변경하지 않습니다.

`DB_RUNTIME_ROLE`은 실제 PostgreSQL 역할명이며 생략하면 datasource 사용자명을 사용합니다. Supabase pooler의 접속 이름에 프로젝트 접미사가 붙는 경우에는 반드시 실제 역할명을 별도로 지정하세요. 역할명은 SQL 식별자로 인용되므로 큰따옴표를 포함하지 않는 운영 역할명을 사용합니다. 역할을 교체하는 경우 이미 적용된 V2가 다시 실행되지 않으므로 별도 권한 부여·회수 절차가 필요합니다. 로컬의 동일 계정 설정도 그대로 사용할 수 있습니다.

## 검증

```sh
./gradlew spotlessApply        # Java 포맷 적용
./gradlew test                 # Docker 없이 보안 경계 테스트
./gradlew check bootJar        # 포맷·단위·PostgreSQL 통합 테스트·실행 jar
```

`integrationTest`와 `check`는 실행 중인 Docker가 필요합니다. Docker가 없으면 실패하며 통합 검증을 조용히 건너뛰지 않습니다. H2를 사용하지 않습니다. Flyway가 app/session 스키마와 세션 테이블을 생성하며 JPA는 스키마를 변경하지 않습니다.

Google 로그인 구현은 아래 안내를 따릅니다. 곡·검색·개인 목록·수집 기능은 기능별로 구현하고 검증 결과를 공유합니다. 최근 요구사항 합의는 [백엔드 구현 기준 보완](../docs/backend-decisions.md)에 기록했습니다.


## Google 가입·로그인

Google 최초 로그인 성공 시 회원을 생성하고 이후에는 같은 Google `sub`로 기존 회원을 조회합니다. 이메일은 변경 가능한 연락 정보이며 같은 이메일을 가진 계정을 자동 병합하지 않습니다. 자체 비밀번호·인증 메일·비밀번호 재설정은 제공하지 않습니다.

### Google Cloud에서 한 번 준비할 설정

1. [Google Cloud Console](https://console.cloud.google.com/)에서 프로젝트를 생성하거나 선택합니다.
2. Google Auth Platform의 Branding에서 앱 이름·지원 이메일을 입력합니다.
3. Audience를 External로 설정하고 개발 중에는 Testing 상태에서 본인의 계정을 Test users에 추가합니다.
4. Clients에서 OAuth 클라이언트를 **Web application** 유형으로 생성합니다.
5. Authorized redirect URIs에 `http://localhost:8080/api/v1/auth/callback/google`을 정확히 등록합니다. 운영 시에는 실제 HTTPS 도메인의 동일 경로를 추가합니다.
6. 발급된 Client ID와 Client secret을 IntelliJ Run Configuration의 환경변수에 입력합니다. 비밀값을 소스·채팅·스크린샷에 넣지 않습니다.

서버 리다이렉트 방식이라 이번 구현에는 JavaScript origin 등록이나 YouTube 권한/API 활성화가 필요하지 않습니다. 로그인 범위는 `openid email`입니다. YouTube 권한은 내보내기 기능에서 추가 동의를 요청합니다.

IntelliJ 환경변수와 프로필:

```text
GOOGLE_CLIENT_ID=<발급받은 값>
GOOGLE_CLIENT_SECRET=<발급받은 값>
SPRING_PROFILES_ACTIVE=local,google
```

터미널에서 환경변수를 별도로 설정했다면:

```sh
./gradlew bootRun --args='--spring.profiles.active=local,google'
```

`local`에서는 콜백 주소를 위 localhost 주소로 기본 설정합니다. 운영은 `prod,google` 프로필과 `GOOGLE_REDIRECT_URI`를 명시합니다. `.env.example`은 참고용이며 자동으로 로드되지 않습니다. `google` 프로필 없이도 서버·DB 테스트는 실행되지만 로그인 시작 API는 503 `GOOGLE_LOGIN_UNAVAILABLE`을 반환합니다.

### API 계약과 브라우저 확인

| 메서드·경로 | 동작 |
|---|---|
| GET `/api/v1/auth/google` | Google 로그인 시작; 브라우저 페이지 이동으로 호출, 선택적으로 `returnTo` 전달 |
| GET `/api/v1/auth/authorize/google` | Spring Security의 OAuth 인증 요청 생성 |
| GET `/api/v1/auth/callback/google` | Google 콜백; 클라이언트가 직접 만들지 않음 |
| GET `/api/v1/auth/csrf` | `{headerName, token}` 반환 |
| GET `/api/v1/me` | `{id, email, role, status}` 반환; 비로그인 401 |
| POST `/api/v1/auth/logout` | CSRF 검증 후 세션·쿠키 제거, 204 |
| POST `/api/v1/me/reauthentications` | CSRF 검증 후 같은 Google 계정 재인증 시작 |
| GET `/api/v1/me/withdrawal-confirmation` | 현재 세션의 탈퇴 확인과 만료 시각 조회 |
| DELETE `/api/v1/me` | 재인증 완료 후 5분 내 탈퇴 확정; CSRF 필요, 204 |

브라우저에서 `http://localhost:8080/api/v1/auth/google`을 열면 로그인 후 `/api/v1/me`로 이동합니다. 원래 화면으로 돌아가려면 `returnTo`에 `/songs?q=cover#results` 같은 사이트 내부 경로를 URL 인코딩하여 전달합니다. 성공·동의 취소·인증 실패 시 저장된 경로에 각각 `loginResult=success`, `cancelled`, `failed`를 붙여 이동합니다. 프론트 화면은 별도 구현이므로 현재 검증은 리다이렉트와 인증 API 응답을 확인합니다.

복귀 경로는 서버의 OAuth 요청에 저장하며 같은 브라우저·일치하는 `state`로 5분 안에 완료해야 합니다. 외부 주소·API 경로·인코딩 우회는 시작 단계에서 400으로 거부합니다. 잘못된 `state`·만료·재사용된 콜백은 복귀하지 않고 401을 반환합니다. 콜백의 `returnTo`는 무시합니다. 계약은 [로그인 복귀 API](../docs/login-return-api.md)를 참고하세요. 프론트는 `loginResult`만으로 로그인 여부를 판단하지 않고 `/api/v1/me`로 확인하며, 저장 의도를 보관·한 번 실행하는 동작은 프론트에서 구현합니다.

로그인 전과 로그인·로그아웃 후에는 CSRF 토큰을 새로 받습니다. 변경 요청에 `X-CSRF-TOKEN` 헤더를 넣고, 로그아웃 성공 시 프론트의 개인 Query 캐시도 제거해야 합니다. 로그아웃은 Stelody 세션만 종료하며 Google 계정을 로그아웃하거나 동의를 철회하지 않습니다.

### 보안·검증 범위

Spring Security의 authorization code + PKCE, state·nonce와 ID 토큰 서명/발급자/대상/만료 검증을 사용합니다. 이메일 검증 여부가 확인된 Google 계정만 가입합니다. DB 역할은 기본 USER이며 Google의 임의 역할 claim으로 관리자 권한을 부여하지 않습니다. 세션 ID는 인증 성공 시 교체되고 JDBC 세션에는 내부 회원 ID와 로그인 시각을 저장합니다. Google access/refresh/ID 토큰을 세션에 보관하지 않습니다.

idle 30분과 로그인 후 절대 12시간 만료를 적용하고, 인증 요청마다 DB 상태·역할을 확인합니다. 정지·삭제된 계정의 접근을 발견하면 해당 계정의 모든 JDBC 세션을 무효화합니다. 관리자 지정은 아래 운영용 명령으로 처리하며 정지 API는 아직 제공하지 않습니다.

탈퇴는 같은 Google 계정 재인증 완료 후 5분 안에 확정합니다. V18이 확인 기록과 제한된 삭제 함수를 추가합니다. 기존 Google 클라이언트·콜백을 재사용하며 추가 환경 설정은 없습니다. 재인증 후 새 CSRF 토큰을 받아야 합니다. YouTube 권한 철회가 실패하면 계정을 유지하고 재시도를 안내합니다. 삭제·감사 익명화·오류 응답·검증 범위는 [회원 탈퇴 API](../docs/account-withdrawal-api.md)를 참고하세요.

```sh
./gradlew integrationTest --tests '*GoogleLoginIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

Google 통합 테스트는 WireMock의 모의 OAuth 서버와 서명된 ID 토큰, 실제 HTTP 쿠키, 역할이 분리된 PostgreSQL을 사용합니다. 실제 Google 클라이언트 없이 실행되며 테스트 데이터는 `.invalid` 이메일만 사용합니다. 실제 Google 동의 화면·클라이언트 설정은 발급 후 브라우저에서 별도 확인해야 합니다.

공식 참고: [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect), [Spring Security OAuth 로그인](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html)

## 공개 곡·멤버 조회

GET `/api/v1/songs`, `/api/v1/songs/{id}`, `/api/v1/members`, `/api/v1/members/{id}`, `/api/v1/members/{id}/songs`는 로그인 없이 조회할 수 있습니다. 검색·필터·커서 및 응답 필드는 [공개 API 계약](../docs/public-catalog-api.md)을 참고하세요.

Flyway V4는 곡·작품·업로드와 참여 관계, 완료된 조회수 집계 조회용 스키마를 구성합니다. V5는 pg_trgm 검색 인덱스를 생성합니다. 기존 Google 회원과 세션 데이터는 유지합니다. 운영 확장 설치 권한은 배포 전에 확인해야 합니다.

테스트 자료는 Testcontainers에만 등록합니다. 실제 곡 자료가 아직 없으면 로컬 목록 API는 빈 items를 반환하며, 초기 콘텐츠 입력은 관리자·자료 등록 단계에서 진행합니다. 노래방·음원 링크·조회수 차트와 수집은 다음 기능에서 추가합니다.

```sh
./gradlew integrationTest --tests '*PublicCatalogIntegrationTest' --tests '*CatalogMigrationIntegrationTest' --no-daemon
```

## 내 즐겨찾기

로그인한 회원은 PUT·DELETE `/api/v1/me/favorites/{songId}`로 저장·해제하고 GET `/api/v1/me/favorites`로 최신 저장 순 목록을 조회합니다. GET `/api/v1/me/favorites/{songId}`는 내 저장 여부를 반환합니다. 변경 요청에는 세션 쿠키와 CSRF 토큰을 함께 보냅니다.

사용자당 기본 5,000곡이며 `FAVORITE_LIMIT`로 조정할 수 있습니다. 공개 상태가 바뀐 곡의 저장 기록은 유지하고 내 목록에 자리표시자를 표시합니다. 상세 계약·오류·검증 명령은 [즐겨찾기 API 문서](../docs/favorites-api.md)를 참고하세요. Flyway V6가 저장 관계와 실행 역할의 SELECT·INSERT·DELETE 권한을 추가합니다.

## 개인 플레이리스트

`/api/v1/me/playlists`에서 목록을 생성·조회하고 목록별 이름 수정·삭제·곡 추가·제거·순서 변경을 제공합니다. 세션의 회원을 소유자로 사용하며 다른 회원의 목록은 404로 처리합니다. 변경 요청에는 CSRF 토큰과 현재 목록 `version`이 필요합니다. 충돌하면 409 `PLAYLIST_CHANGED`를 반환하므로 최신 목록을 다시 조회하세요.

사용자당 기본 50개 목록, 목록당 500곡이며 `PLAYLIST_LIMIT`, `PLAYLIST_ITEM_LIMIT`로 조정합니다. 이용 불가 곡도 저장과 위치를 유지합니다. Flyway V7가 목록·항목 스키마와 실행 역할 권한을 추가합니다. [플레이리스트 API 문서](../docs/playlists-api.md)에 응답·커서·전체 순서 교체 계약을 기록했습니다.

```sh
./gradlew integrationTest --tests '*PlaylistIntegrationTest' --tests '*PlaylistMigrationIntegrationTest' --no-daemon
```

## YouTube 등록 영상 수집

같은 jar에 `--collector`를 전달하면 HTTP 서버 없이 수집 작업을 실행하고 종료합니다. 기본 비활성이며 수집 계정·API 키 설정 전에는 DB와 외부 API에 접속하지 않습니다. 수집 전용 계정은 회원·개인 목록·세션을 읽을 수 없어야 합니다. V8 적용 후 [권한 스크립트](../infra/sql/collector-grants.sql)를 스키마 소유자 계정으로 실행하세요.

허용된 공식 채널의 등록 영상만 갱신합니다. 작업 중복과 부분 실패를 처리하고 완료된 결과만 공개 조회수로 게시합니다. KST 일별 마지막 관측값과 보관 정리도 제공합니다. [수집·설정 안내](../docs/youtube-collector.md)에 Google API 키 발급, DB 계정과 GitHub Actions 설정, 실패·재개 규칙을 정리했습니다. 통계 화면은 후속 단계입니다.

```sh
./gradlew bootJar --no-daemon
java -jar build/libs/stelody-0.0.1-SNAPSHOT.jar --collector
```

## 공식 채널 탐색·관리자 후보 검토

V9·V10 적용 후 [탐색 권한](../infra/sql/discovery-grants.sql)과 [공식 채널 등록](../infra/sql/official-channels.sql)을 스키마 소유자로 수동 적용합니다. 채널 등록은 사용자가 제공한 공식 그룹·개인 채널 11곳만 포함하며 기존 채널과 멤버 자료를 덮어쓰지 않습니다.

`DISCOVERY_ENABLED=true`를 설정하고 프로그램 인수 `--collector --discover`로 실행하면 최신 업로드를 검토 후보로 저장합니다. `DISCOVERY_CLASSIFICATION_ALLOWED`는 기본 false입니다. V19부터 별도 정책 허용·관리자 활성화 조건을 충족하면 명시된 단독 커버를 자동 등록·공개합니다. [자동 공개 설정·보완 API](../docs/cover-auto-publication-api.md)를 참고하세요. 과거 목록은 채널 UUID를 지정한 작은 수동 배치로 탐색합니다.

관리자 전용 GET `/api/v1/admin/reviews`, GET·PATCH `/api/v1/admin/reviews/{id}`로 조회·무시·복원합니다. 변경에는 CSRF 토큰, 현재 후보 버전, 사유가 필요합니다. 일반 회원은 사용할 수 없으며 변경 이력은 트랜잭션으로 기록합니다. 관리자 지정·곡 등록 화면은 후속 작업입니다. [탐색·검토 계약](../docs/video-discovery.md)에 실행 방법, 페이지 재개, 분류 제안, 관리자 API와 권한을 정리했습니다.


## 운영용 관리자 지정

관리자 역할은 공개 API 없이 `--admin-account` 일회성 명령으로 지정합니다. 별도 운영 계정과 `ADMIN_DB_URL/ADMIN_DB_USERNAME/ADMIN_DB_PASSWORD`를 준비하고 `infra/sql/admin-operator-grants.sql`을 스키마 소유자로 적용해야 합니다. 웹·수집 자격 증명을 재사용하지 않습니다. V11은 웹 실행 계정의 회원 역할/상태 변경을 제한하고 감사 기록을 추가합니다. 상세 실행 방법은 [운영용 관리자 지정](../docs/admin-accounts.md)을 참고합니다.

## 관리자 카탈로그 편집

멤버·원곡·곡 편집과 검토 후보 등록·공개 API는 [관리 API 계약](../docs/catalog-management-api.md)을 참고합니다. 모든 관리 API는 현재 ADMIN 세션과 변경 요청 CSRF 토큰을 요구합니다. V12는 카탈로그 편집 권한·링크·노래방·후보 등록 관계를 추가합니다.

## 조회수 추이·급상승

로그인 없이 `GET /api/v1/songs/{id}/views?days=7|30`로 대표 영상의 KST 일별 관측을 조회합니다. `GET /api/v1/songs/trending`은 마지막 성공 게시본 기준 24시간 증가량이며 `TRENDING_ENABLED`와 `TRENDING_POLICY_ALLOWED` 모두 true일 때만 제공합니다. 기본은 비활성입니다. V13 적용 후 수집 실행 계정에 `infra/sql/statistics-collector-grants.sql`을 추가로 적용해야 합니다. 결측·대표 변경·보관·표시 상태는 [통계 API 계약](../docs/view-statistics-api.md)을 참고합니다.

## YouTube 플레이리스트 내보내기

기존 Google 로그인과 별도로 YouTube 추가 동의를 받아 새 비공개 재생목록으로 복사합니다. 이용 불가 곡은 제외하고 진행·제외·실패 결과를 확인하며 같은 작업을 재시도할 수 있습니다. 토큰은 AES-256-GCM으로 암호화하고 기본 활성화 설정은 false입니다. V14와 추가 콜백 주소·암호화 키·OAuth scope 설정, 엔드포인트 및 실제 계정 검증 절차는 [내보내기 API 문서](../docs/youtube-export-api.md)를 참고합니다.

## 관리자 수집 운영

등록 영상·탐색 실행의 최근 상태, 처리 수, 실패 이력과 3슬롯 이상 지연 경고를 관리자 API로 조회합니다. 실패 작업의 수동 재시도는 DB에 접수하고 다음 수집기 실행에서 처리합니다. 현재 ADMIN 세션과 변경 요청 CSRF 토큰이 필요합니다.

V15 적용 후 스키마 소유자로 [운영 요청 수집 권한](../infra/sql/collection-operations-grants.sql)을 추가 적용하고 웹·수집 실행 환경 모두에 `COLLECTION_RETRY_ENABLED=true`를 설정합니다. 기본 비활성이며 GitHub Actions에서는 같은 이름의 저장소 Variable을 사용합니다. 응답·진행 조회·시간 기준·설정과 제한은 [수집 운영 API 계약](../docs/collection-operations-api.md)을 참고합니다.

## 수집 분류 규칙·기념일 검토

관리자는 분류 키워드를 샘플 미리보기로 확인하고 버전·변경 이력을 남겨 수정할 수 있습니다. 탐색기는 실행마다 규칙을 고정하고 후보·실행에 적용 버전을 기록합니다. V16 이후 제목 분류를 켜기 전에 스키마 소유자로 [규칙 읽기 권한](../infra/sql/collection-rule-grants.sql)을 추가 적용하세요. 기존 채널 허용 목록 관리 API를 그대로 사용합니다.

기념일 후보는 대표 영상의 KST 공개일과 확정된 참여 멤버의 생일·데뷔일을 비교합니다. 관리자가 확인한 경우에만 자유 입력 라벨과 특별 목적 표시를 저장하며 확정·무시·해제는 재수집으로 복원하지 않습니다. V17 이후 `SPECIAL_EVENT_POLICY_ALLOWED`가 생성·확정·재검토를 허용하고, `SPECIAL_EVENT_REVIEW_ENABLED`까지 켜면 주기적으로 후보를 생성합니다. 기본은 모두 비활성이며 추가 Google 설정은 없습니다. [API 계약과 적용 순서](../docs/classification-management-api.md)를 참고하세요.
