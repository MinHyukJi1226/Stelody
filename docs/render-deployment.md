# Render + Supabase 배포

## 범위

Render Free 웹 서버와 Supabase Free PostgreSQL을 이용한 외부 배포 시험이다. 서버와 DB는 Singapore에 둔다. Render Free는 요청이 없는 동안 중지되고 다시 요청하면 기동한다. Supabase Free의 자동 백업은 제공되지 않는다. 이 조합의 배포 성공만으로 상시 운영·정기 수집·외부 백업·탈퇴 기록 확보가 완료되지는 않는다.

## 1. DB 준비

Supabase 프로젝트 생성 시 Data API와 새 테이블 자동 노출을 끈다. GitHub 저장소 연동은 필요하지 않으며 스키마 변경은 기존 Flyway로 관리한다. PostgreSQL 17을 확인한다.

Connect → Direct → **Session pooler**의 주소를 사용한다. 포트는 5432이며 Transaction pooler 6543은 세션 잠금이 필요한 수집·마이그레이션에 사용하지 않는다. Database → Settings의 SSL Configuration에서 CA 인증서를 받는다. 인증서와 서버 주소를 검사하는 `sslmode=verify-full`을 유지한다.

연결 문자열에서 **프로젝트에 배정된 전체 호스트**를 복사한다. `aws-[INDEX]-[REGION].pooler.supabase.com`의 클러스터 번호는 지역만으로 알 수 없다. Singapore여도 `aws-0`으로 가정하지 않는다. 환경변수 예시의 `SESSION_POOLER_HOST`를 복사한 전체 호스트로 바꾸고, 사용자 이름의 `PROJECT_REF`도 같은 프로젝트 식별자로 바꾼다.

신규 빈 프로젝트의 관리자 접속으로 `infra/deploy/prepare-supabase-roles.sql`을 실행한다. 앱 스키마나 같은 역할이 이미 있으면 멈춘다. 일반 서버용 역할 준비 SQL의 전역 PUBLIC 권한 철회는 관리형 DB 제공자의 다른 서비스에 영향을 줄 수 있어 Supabase용 절차에서는 수행하지 않는다. 제공자 역할·스키마·기본 권한은 유지하고 새 역할에 필요한 DB 권한만 부여한다.

각 역할에 서로 다른 비밀번호를 대화형 `\password`로 설정하고 `ALTER ROLE <역할> LOGIN;`을 적용한다. 비밀번호를 SQL 파일·명령 인수·Git에 넣지 않는다. 웹에 관리자·collector·operator 자격 증명을 전달하지 않는다.

- 접속 사용자 이름은 `stelody_app.<project-ref>`처럼 프로젝트 식별자를 포함한다.
- Flyway의 `DB_RUNTIME_ROLE`에는 실제 DB 역할 이름인 **`stelody_app`만** 넣는다.
- migrator만 DB CREATE 권한을 가진다. app·collector·operator는 슈퍼유저·역할 생성·DB 생성 권한이 없다.
- 첫 기동이 V19까지 마이그레이션한다. 이후 기존 수집·운영 권한 SQL을 스키마 소유자로 적용한다. 최초 실제 콘텐츠 등록은 기존 명세와 운영 후보를 대조하는 별도 절차다.

## 2. 소스 빌드

Render 전용 `backend/Dockerfile.render`가 Java 21 JDK로 `bootJar`를 만든 뒤 JRE 실행 이미지에 복사한다. 로컬 실행 jar가 없는 새 Git checkout에서도 빌드한다. Java 기반 이미지 digest와 Gradle 배포 SHA-256을 고정한다. 배포 전 CI에서 테스트·포맷 검사를 통과해야 한다.

전용 `Dockerfile.render.dockerignore`는 Gradle wrapper·빌드 설정·main 소스·기동 스크립트만 빌드 문맥에 포함한다. 로컬 env·이전 build·Git·회원 자료·백업은 제외한다. 빌드 인수로 자격 증명을 전달하지 않는다. 기존 Compose용 Dockerfile과 루트의 빌드 문맥 설정은 유지한다.

```sh
docker build --platform linux/amd64 -f backend/Dockerfile.render -t stelody:render-check .
```

실행은 UID 10001이다. 512MB 환경을 위해 JVM heap 비율·직접 메모리·code cache·스레드 크기와 Tomcat 스레드 수를 제한한다. 이 설정은 부하 시험 결과나 운영 서버의 자원 보장을 대신하지 않는다.

무료 CPU 환경의 초기 기동에 맞춰 JVM 컴파일 단계를 C1으로 제한한다. 기동 시 컴파일 비용을 줄이는 대신 장시간 실행의 최적화 수준은 낮아진다. 첫 TLS 연결의 시간 초과로 즉시 종료하지 않도록 Hikari의 초기 연결 재시도 시간을 60초로 설정하고, 초기 마이그레이션 연결도 최대 두 번 재시도한다. 인증서·호스트 검증과 DB 스키마 검증은 유지하며 연결이 계속 실패하면 기동을 실패시킨다.

## 3. Render 입력

| 항목 | 값 |
| --- | --- |
| Source | GitHub의 Stelody 저장소 |
| Language | Docker |
| Branch | 검증·병합된 main |
| Region | Singapore |
| Root Directory | 빈칸 |
| Dockerfile Path | `backend/Dockerfile.render` |
| Docker Build Context | 저장소 루트 `.` |
| Instance Type | Free, $0 |
| Health Check Path | `/actuator/health` |

`infra/deploy/render.env.example`의 `SESSION_POOLER_HOST`, `PROJECT_REF`와 비밀번호·인증서·Google 클라이언트 입력 표시를 모두 실제 값으로 바꾼 뒤 Add from .env로 넣는다. 비공개 실제 설정 파일은 Git에서 제외하고 로컬 권한을 600으로 유지한다. 이 파일 전체·Render 환경변수 화면을 채팅이나 PR에 게시하지 않는다.

- 실행 프로필은 **`prod,google,render`**이다. render만 활성화해서 운영 쿠키·문서 차단을 빠뜨리지 않는다.
- `PORT=10000`을 사용하고 앱은 Render에 전달된 PORT에 바인딩한다.
- `DB_URL`에는 해당 프로젝트의 Connect → Session pooler에서 복사한 전체 호스트·5432·DB 이름, `sslmode=verify-full&sslrootcert=/tmp/stelody-db-ca.crt`를 넣는다. 비밀번호는 URL에서 분리한다.
- `DB_CA_CERTIFICATE_BASE64`는 다운로드한 **공개 CA 인증서**를 한 줄 base64로 인코딩한 값이다. 개인키가 아니다. 기동 스크립트가 임시 파일을 권한 600으로 생성한 뒤 고정 경로에 교체한다. 재시작 시 인증서를 다시 적용하며 유효하지 않은 base64·필수 설정 누락은 기동을 실패시킨다.
- Google 클라이언트 값은 호스팅의 환경변수에만 저장한다. 서버의 `RENDER_EXTERNAL_URL`을 Spring의 render 프로필에서 사용하여 배정된 실제 주소로 두 콜백을 구성한다. 환경변수 값의 문자열 치환을 Render에 맡기지 않는다.
- 별도 도메인이 생기면 `GOOGLE_REDIRECT_URI`, `YOUTUBE_EXPORT_REDIRECT_URI`에 정확한 HTTPS 콜백을 명시해 기본값을 덮어쓸 수 있다.
- 첫 기동은 외부 내보내기·수동 수집 재시도·분류/자동 공개/통계의 정책 플래그를 켜지 않는다. 각 기능의 정책·Google 동의·수집 계정과 정기 실행을 별도로 확인한 뒤 기존 운영 절차에 따라 활성화한다.

운영 env와 CA는 이미지 빌드에 넣지 않는다. 초기 기동에는 웹·마이그레이션 두 계정이 필요하다. 마이그레이션 자격 증명을 웹에서 제거하려면 V19 검증 후 `SPRING_FLYWAY_ENABLED=false`를 설정하고 migration 이름·비밀번호를 제거하여 재기동한다. 다음 스키마 변경 때에는 마이그레이션을 다시 수행하는 절차가 필요하다.

이번 프로젝트의 Supabase에는 로컬 배포 시험으로 V19와 수집·운영 역할 권한을 적용했다. 비공개 `backend/.env.render.local`은 `SPRING_FLYWAY_ENABLED=false`인 웹 계정 전용 파일이며, 초기 마이그레이션 설정은 별도 비공개 파일에 보관했다. 설정 예시는 신규 DB에 대한 초기 기동용이므로 기존 프로젝트에 그대로 덮어쓰지 않는다. 운영 DB의 카탈로그는 아직 비어 있으며 로컬 곡·회원 자료를 자동으로 복사하지 않았다.

## 4. 첫 배포 검증

Render가 표시한 실제 HTTPS 주소를 기록한 뒤 Google OAuth 클라이언트에 아래 주소를 정확히 추가한다. 서비스 이름만으로 URL을 추정하지 않는다.

- `<배정된 HTTPS 주소>/api/v1/auth/callback/google`
- `<배정된 HTTPS 주소>/api/v1/me/youtube/callback`

`/actuator/health`가 UP인지, Flyway V19 완료·웹 계정의 최소 권한·공개 API·개인/관리 접근 차단·CSRF·운영 문서 차단을 확인한다. 이후 실제 로그인과 원래 화면 복귀, HTTPS 쿠키를 확인한다. 최초 카탈로그 등록과 YouTube 연결/내보내기 등 나머지 검증은 [배포·운영 체크](deployment-operations.md)를 따른다.

## 준비 과정의 검증

- `check bootJar`: 단위 테스트 185개·통합 테스트 379개와 포맷 검사 통과.
- 새 소스 빌드 문맥에서 Linux amd64 이미지 빌드 완료. 실행 이미지는 jar·기동 스크립트만 포함하며 비공개 환경변수를 포함하지 않는다.
- 인증서 누락·잘못된 base64 입력은 Java 실행 전에 실패한다. UID 10001 실행과 인증서 권한 600, 같은 컨테이너의 반복 초기화를 확인했다.
- 실제 Supabase에서 V19 완료, app의 스키마 생성 금지, collector의 회원·OAuth 토큰 조회 금지와 민감 함수의 PUBLIC 실행 금지를 확인했다.
- 실제 Supabase에 연결한 로컬 서버에서 공개 API 200, 개인·관리 API와 운영 문서 401, CSRF 누락 403, Google HTTPS 콜백과 Secure·HttpOnly·SameSite=Lax 쿠키를 확인했다.
- Mac의 native arm64 컨테이너에서 512MB·0.1 CPU 제한과 마이그레이션 자격 증명 제거를 적용하여 재시험했다. 기본 연결 설정의 첫 TLS 연결 실패를 재현했으며, C1 컴파일과 초기 연결 재시도 설정으로 약 4분 만에 기동하고 health UP을 확인했다. 기동 후 메모리는 약 250MB였다. 실제 Render의 하드웨어·네트워크와 기동 시간은 다를 수 있다.
- 보완 후 소스에서 amd64 이미지를 다시 빌드했다. 최종 이미지의 동일 jar·기동 스크립트를 native arm64 실행 환경에서 마이그레이션 설정을 포함해 확인했으며, 기존 V19의 검증과 health UP을 통과했다. 이 최종 확인은 512MB·1 CPU 조건이다.
- Render의 실제 HTTPS 주소에서 로그인·콜백·최초 기동 시간은 첫 외부 배포 후 확인해야 한다. Mac에서 amd64를 에뮬레이션한 0.1 CPU 시험은 8분 안에 기동되지 않았으며 OOM은 없었다. 실제 Render 기동 성공으로 기록하지 않는다.

## 공식 문서

- [Render Docker 빌드](https://render.com/docs/docker)
- [Render 기본 환경변수](https://render.com/docs/environment-variables)
- [Render 환경변수 입력](https://render.com/docs/configure-environment-variables)
- [Render Free 제한](https://render.com/docs/free)
- [Supabase 접속·인증서](https://supabase.com/docs/guides/database/connecting-to-postgres)
- [Dockerfile별 빌드 문맥 제외](https://docs.docker.com/build/concepts/context/)
- [Hikari 초기 연결 재시도 설정](https://github.com/brettwooldridge/HikariCP#infrequently-used)
