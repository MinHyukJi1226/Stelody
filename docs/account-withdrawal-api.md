# 회원 탈퇴 API

2026-10-03. 같은 Google 계정으로 다시 인증한 뒤 **인증 완료부터 5분 이내**에 탈퇴를 확정한다. 일반 로그인 성공만으로는 탈퇴를 허용하지 않는다. 모든 경로는 현재 ACTIVE 회원의 세션을 요구하고, POST·DELETE에는 CSRF 토큰이 필요하다. 응답은 `Cache-Control: no-store`다.

## 브라우저 흐름

1. GET `/api/v1/auth/csrf`로 현재 세션의 CSRF 토큰을 받는다.
2. POST `/api/v1/me/reauthentications`를 `X-CSRF-TOKEN` 헤더와 함께 호출한다. 본문은 없다.
3. 응답의 `authorizationUrl`로 브라우저를 이동한다. 현재 로그인한 계정과 같은 Google 계정을 선택한다.
4. 기존 로그인 콜백 `/api/v1/auth/callback/google`이 인증을 처리하고 `/api/v1/me/withdrawal-confirmation`으로 이동한다.
5. 확인 응답이 `reauthenticated: true`이면 **새 CSRF 토큰을 받은 뒤** 5분 안에 DELETE `/api/v1/me`를 호출한다. 본문에 회원 ID를 보내지 않는다.
6. 성공 HTTP 204 후 개인 Query 캐시를 비우고 비로그인 화면으로 전환한다. 실패 응답으로는 탈퇴 완료 화면을 표시하지 않는다.

기존 Google OAuth 웹 클라이언트·로그인 콜백·`openid email` 권한을 재사용한다. 추가 클라이언트·scope·환경변수 설정은 필요 없다. Google 계정 식별은 이메일이 아닌 `sub`를 비교한다. Google 계정 선택 화면을 다시 거치지만 **비밀번호 재입력은 보장하지 않는다**. 실제 재로그인 화면은 Google의 인증 상태에 따라 달라진다. [Google OpenID Connect 문서](https://developers.google.com/identity/openid-connect/reference)

### POST `/api/v1/me/reauthentications`

```json
{
  "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?...",
  "expiresAt": "2026-10-03T06:05:00Z"
}
```

`expiresAt`는 **인증 요청의 만료 시각**이다. 요청 시작부터 5분 안에 콜백을 완료해야 한다. 인증 완료 시 별도의 5분 탈퇴 확인 기간을 부여한다. 새 요청은 같은 세션의 이전 확인을 무효화한다. 마지막 요청만 완료할 수 있다. 다른 브라우저 세션의 확인을 사용할 수 없다.

요청의 state·nonce·PKCE와 Google 서명·발급자·대상·유효기간·이메일 인증을 검증한다. state는 세션과 DB 양쪽에서 확인하고 코드 교환 전 일회성으로 소비한다. 다른 Google 계정 인증은 기존 회원을 전환하거나 신규 회원을 만들지 않는다. 실패한 공개 콜백은 기존 로그인 세션을 유지한다.

### GET `/api/v1/me/withdrawal-confirmation`

```json
{"reauthenticated": true, "expiresAt": "2026-10-03T06:07:00Z"}
```

확인이 없거나 만료되면 `{"reauthenticated":false,"expiresAt":null}`을 반환한다. 회원의 기존 12시간 절대 로그인 기한은 재인증으로 연장하지 않는다. 재인증 성공 시 세션 ID와 CSRF 토큰을 교체하고 확인을 새 세션 ID에 연결한다. Google 로그인 토큰은 JDBC 세션이나 확인 테이블에 보관하지 않는다.

### DELETE `/api/v1/me`

현재 회원과 세션의 유효한 확인을 검증하고 YouTube 연결 철회를 시도한다. 연결이 없거나 철회가 완료된 경우 다음 변경을 한 DB 트랜잭션에서 처리한다.

- 회원의 Google 계정 식별자·이메일과 회원 행 삭제
- 즐겨찾기·개인 플레이리스트·항목·YouTube 연결·동의 요청·내보내기 기록·재인증 확인 삭제
- 해당 회원의 모든 JDBC 세션 삭제와 현재 브라우저의 세션 쿠키 만료
- 관리자 감사 이력의 `actor_id` 익명화, 회원 역할 변경 이력의 `target_id`를 새로운 임의 UUID로 교체

다른 회원의 데이터와 공개 곡·멤버·영상·관리 변경 이력은 유지한다. 기존 YouTube 재생목록과 동영상은 사용자의 YouTube 계정에 남는다. 다시 가입하면 새로운 USER 회원 ID를 만들며 이전 개인 목록과 관리자 역할을 복원하지 않는다.

## 실패와 재시도

오류 응답은 기존 Problem JSON의 `code`, `status`, `title`, `traceId`를 사용한다.

| HTTP | code | 처리 |
|---|---|---|
| 401 | AUTHENTICATION_REQUIRED / SESSION_EXPIRED | 다시 로그인 |
| 401 | GOOGLE_LOGIN_FAILED | 같은 계정으로 재인증을 다시 시작 |
| 403 | ACCESS_DENIED | 현재 세션의 CSRF 토큰·요청 메서드 확인 |
| 409 | REAUTHENTICATION_REQUIRED | 같은 브라우저에서 재인증을 완료한 뒤 다시 확정 |
| 409 | YOUTUBE_EXPORT_BUSY | 내보내기 작업이 끝난 뒤 다시 확정 |
| 409 | YOUTUBE_REVOCATION_PENDING | 연결 철회 완료 후 다시 확정; 확인 만료 시 재인증 |
| 503 | GOOGLE_REAUTHENTICATION_UNAVAILABLE | 기존 Google 로그인 설정 확인 |
| 503 | ACCOUNT_STORAGE_UNAVAILABLE | 계정 처리가 완료되지 않았으므로 잠시 후 재시도 |

철회 실패 시 `REVOKING`과 동의 요청·내보내기 기록 삭제를 커밋하고 회원·개인 목록·확인은 유지한다. 기존 작업자가 연결 철회를 재시도한다. 철회 완료 후 **사용자가 다시 DELETE를 요청해야** 탈퇴한다. 실패한 탈퇴 요청 때문에 나중에 자동으로 회원을 삭제하지 않는다. Google이 이미 무효인 토큰으로 응답한 경우도 철회 완료로 처리한다.

내보내기 작업자와 같은 PostgreSQL advisory lock을 사용해 처리 중인 외부 쓰기와 탈퇴를 동시에 실행하지 않는다. 철회 외부 호출에는 기존 연결·읽기 시간 제한을 적용한다. DB 변경이 실패하면 로컬 삭제·감사 익명화·세션 정리를 롤백한다. 이미 성공한 Google 철회는 DB 롤백으로 되돌릴 수 없으므로 다음 시도에서 무효 토큰 응답을 허용한다.

V18은 확인 테이블과 제한된 `SECURITY DEFINER` 삭제 함수를 추가한다. 웹 역할에는 회원 DELETE나 감사 UPDATE 권한을 넓게 부여하지 않는다. 함수는 고정 `search_path`와 명시적 스키마를 사용하고 PUBLIC 실행 권한을 제거하며, 웹 역할만 실행한다. 함수에서도 회원 상태·확인 기한·세션·연결 철회와 작업 잠금을 재검증한다. 수집·운영 역할은 실행할 수 없다.

확인은 기한이 지나면 즉시 사용할 수 없다. 만료 행은 기본 1분 간격으로 최대 1,000개씩 정리하고 잠긴 행은 다음 실행으로 넘긴다. 같은 회원이 재인증을 시작할 때도 그 회원의 만료 행을 정리한다. 감사의 구조화된 회원 참조를 제거하며, 자유 입력 사유·메모에는 개인정보를 넣지 않는 운영 원칙이 필요하다. 이 기능은 현재 DB의 삭제를 처리한다. 과거 백업의 보관·탈퇴 후 복구 시 삭제 재적용 절차는 배포·복구 작업에서 별도로 정해야 한다.

## 검증 범위

Google OAuth는 WireMock, DB는 PostgreSQL 17.6 Testcontainers의 분리된 migrator·web·collector·operator 역할로 검증한다. 실제 브라우저 HTTP 요청으로 재인증·다른 계정·토큰 검증 실패·취소·state 재사용·세션 교체·탈퇴·재가입을 확인하고, 별도 DB 테스트로 개인 데이터 삭제·감사 익명화·철회 실패·작업 잠금·권한 제한·DB 롤백·동시 요청을 검증한다. 실제 Google 계정의 탈퇴와 사용자 로컬 DB 삭제는 수행하지 않는다.

`GRADLE_USER_HOME=/tmp/stelody-gradle ./gradlew spotlessApply check bootJar --no-daemon` 성공. 단위 108개·통합 325개, 총 433개가 실패·오류·건너뛴 테스트 없이 통과했다. 이 중 Google 로그인·재인증 HTTP 테스트 27개와 데이터 삭제·철회 통합 테스트 10개를 포함한다. 포맷 검사와 실행 JAR 빌드도 통과했다.
