# OpenAPI 계약과 Swagger

## 문서 열기

웹 서버의 `local`·`prod` 프로필에서 다음 주소를 사용할 수 있다. 문서 조회에는 로그인이 필요하지 않으며 Google 클라이언트나 YouTube 키를 추가 발급할 필요는 없다.

| 주소 | 용도 |
| --- | --- |
| `/swagger-ui.html` | 요청·응답·검증 조건을 확인하는 Swagger 화면 |
| `/v3/api-docs` | OpenAPI 3.1 JSON 계약 |
| `/v3/api-docs.yaml` | 같은 계약의 YAML 표현 |

Render 운영 주소는 [Swagger UI](https://stelody-backend.onrender.com/swagger-ui.html), [OpenAPI JSON](https://stelody-backend.onrender.com/v3/api-docs), [OpenAPI YAML](https://stelody-backend.onrender.com/v3/api-docs.yaml)이다. 운영 활성화 변경을 배포한 뒤 사용할 수 있다.

기본 프로필에서는 문서를 비활성화한다. `local`·`prod`에서 활성화하며, 문서 통합 테스트는 격리 DB에서 `test,prod` 설정을 사용해 운영의 기본 활성화와 실제 문서 생성을 검증한다. 수집기·운영 명령은 웹 문서 서버를 실행하지 않는다.

설정은 `springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled`다. 운영에서 환경 변수 `SPRINGDOC_API_DOCS_ENABLED=false`로 명세와 UI를 함께 닫거나 `SPRINGDOC_SWAGGER_UI_ENABLED=false`로 UI만 닫을 수 있다. UI는 명세도 활성화된 경우에만 열리며 문서 경로는 GET만 허용한다. 기존 공개·개인·관리자 API의 접근 조건은 유지한다. 로컬 실행에는 기존 환경 설정을 사용한다.

## 인증과 요청 실행

공개 곡·멤버·추천·통계 조회는 로그인 없이 사용한다. 개인 API는 로그인한 계정의 SESSION 쿠키, 관리자 API는 현재 ADMIN 역할이 필요하다.

1. 같은 브라우저에서 `/api/v1/auth/google?returnTo=...`로 이동해 로그인한다. 이 주소는 브라우저 이동용이다. Swagger의 요청 실행으로 Google 동의 화면을 진행하지 않는다.
2. Swagger에서 GET `/api/v1/auth/csrf`를 실행한다. 응답의 `token`을 복사한다.
3. **Authorize → csrf**에 그 값을 입력한다. 변경 요청에 `X-CSRF-TOKEN`으로 전달한다. SESSION은 HttpOnly 쿠키이므로 브라우저가 자동 전송한다. 쿠키 값을 입력하거나 Google 비밀키를 입력하지 않는다.
4. 로그인·로그아웃 뒤에는 CSRF 토큰을 다시 받아 입력한다. 페이지를 새로 열면 저장된 Swagger 인증 값은 유지하지 않는다.

Swagger의 변경 요청도 실제 DB를 변경한다. 개인 목록 생성·삭제, 회원 탈퇴, 관리자 수정, YouTube 내보내기를 실행하면 기존 API와 동일하게 동작한다. 계정·실제 목록을 바꾸는 시험은 그 목적에 맞는 테스트 자료로 진행한다.

프론트의 로그인 복귀·저장 의도 실행은 [로그인 복귀 계약](login-return-api.md), 재인증·탈퇴는 [탈퇴 계약](account-withdrawal-api.md)을 따른다. Google OAuth 내부 시작·콜백은 Spring Security 필터가 처리한다. 프론트는 공개 로그인 시작 주소를 사용한다. 로그아웃 POST는 필터 처리 경로지만 계약에 별도로 포함한다.

## 계약의 범위

컨트롤러와 DTO에서 경로·요청·응답·Bean Validation 제약을 생성한다. 같은 이름의 중첩 응답 객체는 패키지명을 포함해 구분한다. 관리자 공용 상세 메서드의 `Object` 응답은 경로별 멤버·아티스트·원곡·곡·채널 스키마로 명시한다. 카탈로그 생성 201, 내보내기·수집 재시도 접수 202, YouTube 콜백 303, 본문 없는 204 응답을 실제 처리에 맞춘다.

성공 스키마와 공통 업무 오류 `application/problem+json`을 구분한다. 오류는 `type`, `title`, `status`, `instance`, `code`, `fieldErrors`, `traceId`이며 현재 `fieldErrors`는 빈 배열이다. `default` 응답은 기능별 업무 오류를 설명한다. 세부 오류 코드·버전 충돌·이용 불가·정책 설정은 다음 계약을 함께 참고한다.

- [공개 카탈로그](public-catalog-api.md), [조회수·급상승](view-statistics-api.md)
- [즐겨찾기](favorites-api.md), [플레이리스트](playlists-api.md), [YouTube 내보내기](youtube-export-api.md)
- [카탈로그 관리](catalog-management-api.md), [후보 탐색·검토](video-discovery.md)
- [수집 운영](collection-operations-api.md), [분류·기념일 검토](classification-management-api.md), [커버 자동 공개](cover-auto-publication-api.md)

## 프론트에 전달할 JSON

```sh
cd backend
./gradlew integrationTest --tests com.stelody.OpenApiIntegrationTest --no-daemon
```

Docker가 필요하다. 분리된 PostgreSQL 역할을 사용하는 임시 DB에서 애플리케이션을 실행하며, 실제 Google·YouTube 연결 없이 명세를 검증한다. 테스트가 성공하면 `backend/build/openapi/stelody-openapi.json`을 생성한다. 전체 검증 `./gradlew check bootJar --no-daemon`에도 포함된다. CI는 검증한 JSON을 `backend-openapi-<커밋 SHA>` 아티팩트로 보관한다. 프론트의 타입 생성·연동은 이 파일과 해당 커밋을 기준으로 한다.

문서에는 실제 계정·DB 자료·OAuth 키·토큰을 넣지 않는다. API 경로의 누락, 스키마 참조, 인증·CSRF, 응답 코드, 개발 문서 접근과 운영 차단을 테스트한다.

의존성은 [springdoc 공식 안내](https://springdoc.org/)의 Spring Boot 4용 3.x 계열을 사용한다. 문서 노출·스키마 이름 관련 설정은 [공식 속성 문서](https://springdoc.org/properties.html)를 따른다. 현재 프로젝트의 Spring Boot 4.1.1과의 호환성은 컴파일과 실제 문서 통합 테스트로 확인한다.
