# 백엔드 배포·운영

## 현재 상태

이 문서는 Linux 서버에서의 Compose 운영과 백업·복구 준비를 정리한다. 무료 외부 시험 구성인 Render + Supabase의 빌드·접속 설정은 [Render 배포 절차](render-deployment.md)를 따른다. 두 구성 모두 프론트 구현은 포함하지 않는다. 아래의 최초 로컬 검증 기록은 외부 배포 완료를 뜻하지 않는다. 이후 확인한 실제 운영 결과와 미완료 항목은 [2026-10-05 운영 검증](operations-verification.md)에 기록했다. 외부 암호화 보관소는 [Backblaze B2 백업 준비](offsite-backup.md)를 따른다.

| 준비 항목 | 위치 |
| --- | --- |
| Java 21 실행 이미지 | `backend/Dockerfile`, 루트 `.dockerignore` |
| 운영 웹 실행 | `infra/deploy/compose.yml` |
| 웹 설정 예시 | `infra/deploy/web.properties.example` |
| 신규 DB 역할 준비 | `infra/deploy/prepare-roles.sql` |
| 백업·복구와 보관 만료 | `infra/backup/database.py` |
| 복구 후 안전 정리 | `infra/backup/restore-sanitize.sql` |
| Linux 일일 백업 예시 | `infra/backup/stelody-backup.service`, `.timer` |
| 기존 정기 수집 | `.github/workflows/youtube-collector.yml` |

## 1. 환경 결정

배포 시 다음을 정한다. 설정 값·비밀번호는 호스팅의 비밀 저장소 또는 저장소 밖의 비공개 파일에 보관한다.

- Java 21 이미지를 실행할 서버와 운영 PostgreSQL 17. CPU 아키텍처에 맞는 이미지를 빌드한다.
- HTTPS 도메인과 TLS 종료 위치. 서버의 웹 포트는 루프백에만 열고 같은 호스트의 HTTPS 프록시에서 접근한다. 원격 프록시라면 플랫폼의 비공개 네트워크를 사용하도록 연결을 변경한다.
- DB 인증서·접속 방화벽, 웹·수집기·백업의 연결 경로. DB URL은 `sslmode=verify-full`이며 필요한 CA 파일을 해당 프로세스에 읽기 전용으로 제공한다.
- 암호화된 외부 백업 저장소와 7일 만료 정책. 서버 디스크만으로는 서버 장애를 복구할 수 없다.
- 백업 이후 탈퇴 기록을 DB 백업과 독립적으로 보관하는 방법. 제공자 변경 로그/CDC 또는 검증 가능한 별도 기록을 이용한다. 이 기록을 완전하게 확보할 수 없으면 회원 데이터 복구 후 공개 재개를 허용하지 않는다.

로컬 수집기는 GitHub 호스팅 runner에서 로컬 DB에 연결할 수 없다. 운영 DB를 준비하기 전에는 저장소 `COLLECTOR_ENABLED`를 켜지 않는다. DB를 인터넷 전체에 열어 runner 연결 문제를 해결하지 않는다. 제공자의 안전한 접속 방식 또는 해당 비공개 네트워크의 runner를 선택한다.

## 2. DB 역할과 최초 기동

신규 빈 DB에서 관리자 접속으로 `prepare-roles.sql`을 실행한다. 기존 역할이 있으면 실패하며 임의로 변경하지 않는다. 관리형 DB에서 역할 생성 권한이 다르면 같은 권한을 제공자의 관리 절차로 준비한다.

```sh
psql -X -v ON_ERROR_STOP=1 -f infra/deploy/prepare-roles.sql
```

생성된 역할은 처음에 NOLOGIN이다. 관리자의 대화형 psql에서 각 역할에 `\password stelody_migrator`처럼 비밀번호를 설정한 뒤 `ALTER ROLE stelody_migrator LOGIN;`을 실행한다. app·collector·operator도 같은 방법으로 준비하며 서로 다른 비밀번호를 사용한다. 비밀번호를 SQL 파일이나 명령행에 넣지 않는다.

- migrator: 스키마 소유·마이그레이션. 슈퍼유저·역할 생성 권한 없음.
- app: 웹 요청·세션. 마이그레이션 역할의 멤버가 아님.
- collector: 별도 비웹 수집. 회원·세션·개인 목록·OAuth 토큰 접근 없음.
- operator: 일회성 관리자 지정. 웹·수집에 자격 증명 전달 금지.

기존 앱의 Flyway가 기동 시 V19까지 적용한다. 웹 연결 풀은 app 역할, Flyway 연결은 migrator 역할이다. 최초 기동에는 두 자격 증명이 필요하다. 운영 파일은 이에 맞춰 준비한다. 배포가 끝난 뒤 `spring.flyway.enabled=false`로 웹을 재기동하는 운영 방식에서는 마이그레이션 비밀번호를 파일에서 제거할 수 있다. 다음 배포에 마이그레이션을 다시 적용할 때에는 준비된 두 역할로 먼저 기동·검증하고, 마이그레이션을 끈 웹 설정으로 교체한다. 두 프로필 구성에서 DB 스키마 검증은 유지한다.

V19 적용 후 **스키마 소유자**로 다음 기존 권한 파일을 적용한다.

```sh
psql -X -v ON_ERROR_STOP=1 -f infra/sql/collector-grants.sql
psql -X -v ON_ERROR_STOP=1 -f infra/sql/discovery-grants.sql
psql -X -v ON_ERROR_STOP=1 -f infra/sql/statistics-collector-grants.sql
psql -X -v ON_ERROR_STOP=1 -f infra/sql/collection-operations-grants.sql
psql -X -v ON_ERROR_STOP=1 -f infra/sql/collection-rule-grants.sql
psql -X -v ON_ERROR_STOP=1 -f infra/sql/cover-publication-grants.sql
psql -X -v ON_ERROR_STOP=1 -f infra/sql/admin-operator-grants.sql
```

권한 파일의 역할 이름이 실제 역할과 일치해야 한다. 관리자는 [관리자 지정 절차](admin-accounts.md)로 필요한 실제 계정 UUID에만 지정한다. 기존 로컬 회원을 자동으로 운영 관리자로 복사하지 않는다.

## 3. 웹 실행과 배포

검증된 커밋에서 저장소 루트 기준으로 빌드한다. Docker 빌드 문맥에는 실행 jar·Dockerfile·상태 확인 파일만 포함되며 `.env`, 개인 설정, 백업은 들어가지 않는다. 태그에는 커밋 SHA를 사용하고, 원격 배포에는 확인한 이미지 digest를 고정한다. 기반 Java 이미지는 실제 릴리스마다 보안 갱신을 확인한다.

```sh
cd backend
./gradlew check bootJar --no-daemon
cd ..
docker build -f backend/Dockerfile -t stelody:<commit-sha> .
```

`web.properties.example`을 저장소 밖의 비공개 디렉토리로 복사하고 실제 값을 입력한다. Linux에서는 해당 파일을 UID/GID 10001이 읽을 수 있도록 소유권을 설정하고 권한은 600, 부모 디렉토리는 700으로 둔다. properties의 값은 따옴표로 감싸지 않으며, 문자 `\`는 `\\`로 이스케이프한다. Compose의 파일 Secret은 암호화 저장소가 아니므로 호스트 접근 제어도 필요하다. [Docker Secrets 설명](https://docs.docker.com/compose/how-tos/use-secrets/).

```sh
export STELODY_IMAGE=stelody:<commit-sha>
export WEB_SETTINGS_FILE=/etc/stelody/private/web.properties
export DB_CA_FILE=/etc/stelody/db-ca.crt
export BACKEND_PORT=8080
docker compose -f infra/deploy/compose.yml up -d --wait --wait-timeout 120
```

웹은 비루트 UID 10001, 읽기 전용 파일시스템, 제거된 Linux capabilities, 1GB 메모리 제한, `/tmp` 임시 메모리 공간으로 실행한다. 상태 확인은 DB를 포함한 `/actuator/health`의 HTTP 200을 요구한다. unhealthy 표시만으로 Docker가 컨테이너를 다시 시작하지는 않으므로 호스팅의 상태 감시·알림과 연결한다. 로그는 회전하며 비밀 파일·요청 쿠키·OAuth 토큰을 로그에 추가하지 않는다. [Compose 서비스 설정](https://docs.docker.com/reference/compose-file/services/).

`DB_CA_FILE`의 인증서는 컨테이너 `/run/secrets/db-ca.crt`에 읽기 전용으로 마운트되며 URL의 `sslrootcert`가 그 파일을 가리킨다. 파일은 UID 10001이 읽을 수 있어야 한다. `verify-full`로 인증서와 호스트명을 모두 검사한다. [pgJDBC TLS 설명](https://jdbc.postgresql.org/documentation/ssl/).

Google에 운영 HTTPS 주소 두 개를 정확히 등록한다.

- `/api/v1/auth/callback/google`: 일반 로그인·탈퇴 재인증
- `/api/v1/me/youtube/callback`: YouTube 추가 동의

`GOOGLE_REDIRECT_URI`와 `YOUTUBE_EXPORT_REDIRECT_URI`는 해당 주소와 일치해야 한다. `prod`는 Secure·HttpOnly·SameSite=Lax 쿠키를 유지하고 OpenAPI/Swagger를 닫는다. 고정된 외부 콜백을 사용하므로 전달 헤더 처리를 임의로 켜지 않는다. 테스트 대상 밖의 실제 사용자에게 서비스를 공개하려면 Google의 앱 게시·권한 검증 조건도 확인한다.

처음 운영 카탈로그를 등록할 때는 [초기 등록 명세](initial-catalog-registration.md)를 운영 DB의 후보·채널·현재 영상 상태와 대조하여 적용한다. 로컬 `reviewId`나 개발용 회원·개인 목록을 그대로 초기 데이터로 복사하지 않는다. 등록을 Flyway나 앱 자동 초기화로 실행하지 않는다. 아래 백업 복구는 **이미 운영 중인 DB의 재해 복구** 절차이며 최초 콘텐츠 등록을 대신하지 않는다.

롤백은 직전 검증 이미지로 바꾸고 상태를 확인한다. 이전 앱이 새 스키마와 호환되는지 먼저 확인하며, 운영 DB를 이전 백업으로 덮어써서 앱 롤백을 대신하지 않는다.

## 4. 정기 수집 활성화

기존 workflow는 매시간 UTC :17에 등록 영상을 관측하고, 짝수 UTC 시간에는 신규 영상 탐색을 함께 수행한다. 예약 실행은 지연될 수 있다.

1. 운영 DB 마이그레이션·수집 권한·공식 채널을 준비한다.
2. GitHub Secrets에 `COLLECTOR_DB_URL`, `COLLECTOR_DB_USERNAME`, `COLLECTOR_DB_PASSWORD`, `YOUTUBE_API_KEY`를 저장한다. Supabase는 공개 CA의 `COLLECTOR_DB_CA_CERTIFICATE_BASE64`도 저장하고 JDBC URL에 `sslrootcert=/tmp/stelody-collector-db-ca.crt`를 지정한다. 웹·관리자·마이그레이션 자격 증명은 전달하지 않는다. 비밀값을 로그나 채팅에 출력하지 않으며 대리 등록은 대상과 전달할 값을 명시적으로 승인받은 뒤 진행한다.
3. `COLLECTOR_ENABLED=true`를 설정하고 수동 실행 한 번으로 완료·공개 조회수·최종 성공 시각을 확인한다.
4. 신규 탐색은 `DISCOVERY_ENABLED=true`, 합의한 분류는 `DISCOVERY_CLASSIFICATION_ALLOWED=true`를 별도로 적용해 수동 시험한다.
5. 수동 재시도는 웹·수집 양쪽 `COLLECTION_RETRY_ENABLED=true`를 설정한다.
6. 자동 커버 공개는 별도 정책 확인·`COVER_AUTO_PUBLICATION_POLICY_ALLOWED`·관리자 활성화가 모두 필요하다. [자동 공개 절차](cover-auto-publication-api.md)를 따른다.
7. GitHub 예약 실행 기록과 관리자 `/api/v1/admin/collection-status`에서 실제 다음 실행을 확인한다. 슬롯 3개 이상 지연·실패·장시간 실행을 호스팅의 알림에 연결한다.

급상승·기념일 후보·자동 공개의 정책 플래그는 운영 준비만으로 true로 바꾸지 않는다. 동시 실행 잠금은 기존 DB 로직과 workflow concurrency를 사용한다. 별도 서버 타이머로 같은 수집을 이중 예약하지 않는다.

## 5. 백업과 복구

PostgreSQL 17의 `psql`, `pg_dump`, `pg_restore`, Python **3.11 이상**이 필요하다. 연결은 libpq의 `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGSSLMODE=verify-full`, `PGSSLROOTCERT`, 비공개 `PGPASSFILE`로 제공한다. 비밀번호를 URL/명령 인수에 넣지 않는다.

현재 절차는 V19 전용이며 스키마 변경 시 제외 대상·정리 SQL을 검토하고 복구 시험을 갱신해야 한다. 백업은 app/session 스키마, pg_trgm 확장, Flyway 이력·ACL·데이터를 하나의 일관된 custom archive로 저장한다. global 역할·비밀번호는 덤프하지 않는다. 확장이 제공자의 별도 스키마에 설치돼 있으면 복구 대상에 그 빈 스키마를 먼저 준비한다. [pg_dump 공식 설명](https://www.postgresql.org/docs/17/app-pgdump.html).

보존: 카탈로그·후보와 검수·회원·즐겨찾기·개인 목록. 제외: 로그인 세션·탈퇴 재인증·YouTube 토큰/동의/내보내기 작업·조회수 표본/게시 수치·수집 실행/대상/재시도 작업. 복구 후 로그인과 YouTube 연결은 다시 해야 하며 과거 진행 중 작업을 자동 재개하지 않는다. 조회수는 새 수집으로 다시 시작하며 만들어 넣지 않는다.

```sh
python3 infra/backup/database.py prune --directory /var/lib/stelody-backups
python3 infra/backup/database.py backup --directory /var/lib/stelody-backups
```

디렉토리는 700, 덤프·명세·로그는 600이다. SHA-256과 생성 시각을 명세에 기록하고 미완성 백업을 완료 폴더로 게시하지 않는다. 최대 7일을 보관하며 API 원본·기념일 근거의 만료가 먼저 오면 전체 백업 만료도 그 시각으로 줄인다. 만료 원본이 이미 남은 DB는 백업 전에 기존 정리 절차를 실행해야 한다. 만료 백업은 복구를 거부하고 prune이 삭제한다. 스크립트 자체는 파일을 암호화하지 않는다. 암호화 디스크와 접근 통제된 외부 저장소를 사용하고, 외부 복제본에도 명세의 `expiresAt`을 따라 만료를 적용해야 한다. SHA-256은 파일 손상을 검출하며 악의적인 덤프를 신뢰할 근거는 아니다.

강제 종료로 남은 `.partial-*` 임시 백업에도 같은 만료 기준을 적용한다. 덤프 시작 전에 비공개 `retention.json`에 생성·만료 시각을 기록하고 파일과 디렉토리를 동기화한다. 기록이 없거나 손상된 이전 임시 폴더는 디렉토리 수정 시각에서 7일이 지나면 정리한다. 폴더·만료 기록의 심볼릭 링크는 따라가지 않는다.

백업과 prune은 저장 디렉토리의 비공개 `.backup.lock`을 공유한다. 백업 중에는 prune이 `skippedActiveBackup: true`를 출력하고 다음 정리 주기로 넘긴다. 같은 디렉토리에서 백업을 중복 실행하면 실패한다. 덤프·목록 검증 자식 프로세스도 잠금을 이어받아 Python 프로세스만 강제 종료되어도 실행 중인 파일을 삭제하지 않는다. 잠금 파일은 삭제하지 않으며 프로세스가 끝나면 잠금은 운영체제가 해제한다. `skippedActiveBackup`이 계속 발생하면 장시간 실행 중인 백업 프로세스를 확인한다. 이 절차는 POSIX 파일 잠금을 지원하는 Linux/macOS와 로컬 저장 디렉토리를 전제로 한다.

Linux에서는 백업과 시간별 prune service/timer의 계정·Python 경로·저장 경로를 맞춰 설치한다. `backup.env`는 비공개 libpq 연결 설정만 포함하고 `PGPASSFILE`도 service 계정이 읽을 수 있어야 한다. 백업 작업은 스키마 소유자로 접속하며 웹·수집기에 이 접속 파일을 제공하지 않는다. timer는 UTC 03:40, 최대 5분 분산, 꺼진 서버의 누락 실행 보완을 사용한다. 별도 prune timer는 백업 실패 여부와 무관하게 매시간 만료 폴더를 정리한다. 디스크 정리와 외부 저장소 수명 주기가 실제로 동작하는지 확인해야 한다. 외부 복제·실패 알림은 선택한 저장소/호스팅 설정에서 연결한다. 기본 일일 주기에서는 최대 하루 분량의 변경 손실이 생길 수 있으므로 운영 요구가 더 짧으면 제공자의 PITR/WAL 보관을 함께 사용한다.

### 복구 순서

1. 웹·수집·내보내기 작업을 중지하고 백업 이후 탈퇴 기록을 확보한다. 기록이 없거나 누락 가능성이 있으면 회원 데이터 공개를 재개하지 않는다.
2. **기존 DB와 이름이 다른 빈 DB**를 준비한다. 원래 스키마 소유자와 동일한 역할명으로 접속한다. 원래 ACL에서 참조하는 웹·수집·운영 역할도 미리 생성하되 최소 권한·별도 비밀번호를 유지한다.
3. 다음 JSON을 비공개 파일로 작성한다. 시각은 실제 서비스 중지 시각과 그 시각까지 삭제 기록을 검증한 시각이다. 미래 시각을 넣지 않는다. 중지 후 30분을 넘기면 서비스를 계속 중지한 채 중지 상태와 기록을 다시 확인하고 확인 시작 시각을 `servicesStoppedAt`에 갱신한다. `withdrawnUserIds`는 해당 백업 이후 탈퇴한 이전 회원 UUID의 전체 목록이다. 재가입한 새 UUID와 구분한다.

```json
{
  "complete": true,
  "servicesStoppedAt": "<실제 중지 시각 ISO-8601 및 시간대>",
  "verifiedThrough": "<중지 시각 이후 기록 확인 시각>",
  "withdrawnUserIds": ["<탈퇴한 회원 UUID>"]
}
```

4. 복구 연결 환경의 `PGDATABASE`를 새 대상 DB로 지정하고 실행한다. 원래 DB·기존 데이터가 있는 DB·만료/손상 백업·미확인 삭제 목록은 거부한다.

```sh
python3 infra/backup/database.py restore \
  --backup /var/lib/stelody-backups/stelody-<시각> \
  --target stelody_restore \
  --withdrawals /etc/stelody/private/withdrawals.json
```

스키마·자료·ACL 복구는 한 트랜잭션이다. ACL을 생략하지 않으므로 SECURITY DEFINER 함수의 PUBLIC 실행 철회가 유지된다. 트리거·FK를 끄거나 기존 DB를 DROP하지 않는다. 이후 정리와 탈퇴 재적용도 한 트랜잭션으로 수행한다. [pg_restore 공식 설명](https://www.postgresql.org/docs/17/app-pgrestore.html).

5. 복구 후 오래된 API 원본·가용 상태·기념일 근거를 관측 시각 기준으로 정리한다. 조회수 포인터와 실행 소유 토큰을 초기화하고 자동 공개를 끈다. 탈퇴 UUID에 해당하는 계정·개인 자료를 삭제하고 역할 변경 감사 대상은 익명화한다. 관리자의 자유 입력 메모에 남은 개인정보는 별도로 검토한다.
6. 같은 릴리스로 기동하여 Flyway 이력·JPA 검증, 카탈로그 ID·관계·개인 목록과 권한을 확인한다. 외부 작업은 꺼둔 상태로 재로그인·연결 흐름을 검증한다.
7. 삭제 대상 잔존 0, 개인 자료 일치, 세션·토큰 0, 정책/권한을 확인한 뒤 운영 접속 경로를 새 DB로 전환한다. 수집을 한 번 실행하고 상태·신규 조회수 관측을 확인한 뒤 정기 실행을 복구한다. 실제 YouTube에 이미 만들어진 목록은 로컬 작업 제외로 삭제되지 않는다.

복구 도구는 `readyForPublicTraffic=false`로 끝난다. 이 출력은 실패가 아니라 후속 검증·접속 전환 전 상태다. 정리 단계에서 실패하면 시험 DB를 오프라인으로 유지하고 비공개 로그를 확인한다. 기존 DB에는 재시도하지 않는다.

## 6. 공개 전 API 확인

운영 HTTPS 주소에서 다음을 확인하고 시각·릴리스 SHA·결과를 기록한다.

- `/actuator/health` UP, DB/비밀 상세 미노출
- 비로그인 곡·멤버 목록/검색/상세/추천, 페이지 전체의 ID·관계·개수
- `/me`·개인 목록·관리 API 비로그인 401, 일반 회원의 관리 접근 403
- 변경 API CSRF 누락 403, 로그인 후 새 CSRF로 정상 처리
- Google 로그인, 외부 returnTo 차단, 내부 returnTo 복귀, 로그아웃
- 즐겨찾기·플레이리스트 저장·순서·삭제와 사용자 간 접근 차단
- YouTube 추가 동의·연결·작은 새 비공개 목록 내보내기·제외/실패/재시도
- HTTPS 쿠키 Secure/HttpOnly/SameSite, 운영 OpenAPI/Swagger 차단
- 수집 수동·예약 실행, 재시도 접수, 수집 권한 제한
- 복구 결과 검증과 삭제 재적용; 탈퇴 흐름은 전용 시험 계정으로만 확인

## 이번 로컬 검증 (2026-10-04)

- 실제 로컬 DB를 읽기 전용으로 백업하고 **별도 PostgreSQL 17 컨테이너**에 복구했다. 검색 확장 pg_trgm 포함·FK·ACL 복구를 확인했다.
- 공개 360곡·11명·검색/추천 자료를 복원했다. 원래 로컬 DB를 덮어쓰거나 사용자 계정을 변경하지 않았다.
- 운영 Compose 구성으로 비루트 UID 10001·읽기 전용 파일시스템·별도 비관리자 웹 역할을 사용해 기동했다. 상태 UP, 공개 360곡·11명·20개 중복 없는 추천, 개인/관리 비로그인 401, CSRF 누락 403, Secure/HttpOnly/SameSite 쿠키, 운영 문서 차단을 확인했다.
- 웹 역할의 스키마 CREATE·Flyway 이력 SELECT와 PUBLIC의 탈퇴 함수 실행이 금지되는지 확인했다.
- 별도 가짜 회원 두 명의 백업을 다시 복구해 유지 회원의 즐겨찾기·목록 항목 보존, 탈퇴 회원·목록 삭제와 감사 참조 익명화, YouTube 토큰·재인증·조회수 표본 제외를 확인했다.
- 복구 거부 조건 8개 테스트와 기존 `check bootJar` 전체 검증(단위 181개·통합 379개)을 통과했다. 신규 역할 준비 SQL도 빈 PostgreSQL에서 네 역할의 NOLOGIN·비관리자 권한을 확인했다.
- 리뷰 수정 후 백업 보호 테스트를 총 15개로 확장해 통과했다. 합성 덤프와 모의 DB 응답으로 실제 백업 프로세스를 SIGKILL 종료해 임시 폴더 잔류를 재현했다. 남은 덤프 자식이 실행 중일 때 정리를 건너뛰고, 자식 종료 후 만료 임시 폴더를 삭제하는지 확인했다. 이전 임시 폴더·손상된 기록·짧아진 원본 만료·심볼릭 링크·동시 실행도 검증했다.

이 시험은 격리된 로컬 HTTP·시험 DB 연결을 사용했다. 실제 운영 HTTPS·DB TLS 인증서·Google 운영 동의·GitHub Secrets/예약 실행·외부 암호화 백업·탈퇴 기록 확보 방식은 서버와 도메인 결정 후 적용·검증한다.
