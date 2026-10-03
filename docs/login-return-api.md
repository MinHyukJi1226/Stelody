# 로그인 후 원래 화면 복귀

## 로그인 시작

`GET /api/v1/auth/google`에 선택적으로 `returnTo`를 전달한다. 브라우저 페이지 이동으로 호출한다. 직접 OAuth 요청을 만드는 `GET /api/v1/auth/authorize/google`에도 같은 검증을 적용한다.

```js
const returnTo = window.location.pathname + window.location.search + window.location.hash;
window.location.assign('/api/v1/auth/google?returnTo=' + encodeURIComponent(returnTo));
```

예를 들어 `/songs?q=cover&member=rin#results`를 전달하면 검색 조건과 화면 위치를 보존한다. 경로는 Google에 전달하지 않고 서버의 OAuth 요청 속성으로 JDBC 세션에 저장한다. 추가 환경 변수·Google 콜백 설정·DB 마이그레이션은 필요 없다.

### 허용 경로

- `/`로 시작하는 사이트 내부 UI 경로. 입력과 ASCII URL 인코딩 결과 모두 최대 2,048자.
- 쿼리와 fragment를 포함할 수 있다. 한글은 ASCII URL 인코딩으로 변환한다.
- 외부 URL, `//`로 시작하는 주소, 역슬래시, 제어 문자·공백, `.`/`..` 경로 구간, 경로 매개변수(`;`)를 거부한다.
- 인코딩된 경로 구분자·역슬래시·중첩 경로 인코딩과 `/api`, `/actuator` 경로를 거부한다.
- 빈 값·중복 `returnTo`도 400 `INVALID_LOGIN_RETURN`이다. 이 단계에서는 OAuth 세션을 만들거나 Google로 이동하지 않는다.

민감한 값은 URL에 넣지 않는다. 시작·콜백 응답에는 `Cache-Control: no-store`, `Referrer-Policy: no-referrer`를 적용한다.

## 콜백 결과

같은 브라우저 세션에서 OAuth 요청의 `state`와 일치하고, 저장 후 5분 안에 완료한 경우에만 복귀한다. Google 콜백의 `returnTo`는 무시한다. 성공 시 기존 로그인과 동일하게 세션 ID를 교체하고 내부 회원 정보만 저장한다.

| 결과 | 저장된 경로에 추가하는 쿼리 |
|---|---|
| 가입·로그인 성공 | `loginResult=success` |
| Google 동의 취소 (`access_denied`) | `loginResult=cancelled` |
| 토큰 교환·ID 토큰 검증 등 인증 실패 | `loginResult=failed` |

기존 쿼리와 fragment는 보존한다. 전달된 경로에 `loginResult`가 이미 있으면 인코딩된 이름을 포함해 모두 제거하고 서버 결과 하나로 교체한다. 취소·실패 자체는 이전에 로그인한 계정의 세션을 제거하지 않는다.

잘못된 `state`, 다른 브라우저, 5분 만료, 재사용한 콜백은 JSON 401 `GOOGLE_LOGIN_FAILED`를 반환하고 복귀하지 않는다. 만료된 요청도 소모하여 재사용을 막고 토큰을 교환하지 않는다. 제공자 콜백이 요청 확인 단계에 도달하지 못한 경우에도 저장된 복귀 경로를 사용하지 않는다.

세션에는 진행 중인 OAuth 요청 하나만 저장한다. 같은 브라우저에서 새 로그인을 시작하면 이전 요청을 대체하고, 이전 탭의 콜백은 401이다. 잘못된 `state`로는 최신 요청을 소모할 수 없다. 새 요청에 `returnTo`가 없으면 이전 경로를 재사용하지 않는다.

`returnTo` 없이 시작하면 기존 동작을 유지한다. 성공은 `/api/v1/me`, 실패·취소는 JSON 401이다. 탈퇴용 `POST /api/v1/me/reauthentications`는 기존 `/api/v1/me/withdrawal-confirmation`으로 이동하며 일반 로그인 복귀 경로를 적용하지 않는다.

## 프론트에서 처리할 동작

`loginResult`는 화면 안내용 값이며 인증 증명이 아니다. 프론트는 `/api/v1/me`로 실제 로그인 여부를 확인하고 로그인 후 CSRF 토큰을 새로 받아야 한다. 저장하려던 즐겨찾기·플레이리스트 동작은 프론트가 보관하고, 인증 확인 후 해당 API를 한 번 호출한다. 취소·실패 시 대기 중인 동작을 실행하지 않는다.

이번 변경은 백엔드의 안전한 리다이렉트 처리다. 프론트 화면과 저장 의도 보관·실행은 포함하지 않는다. 복귀 화면이 별도 프론트 서버에 있다면 운영 프록시가 사이트 내부 UI 경로를 그 서버로 연결해야 한다.

## 검증

```sh
cd backend
GRADLE_USER_HOME=/tmp/stelody-gradle ./gradlew check bootJar --no-daemon
```

`LoginReturnTest`·`LoginReturnRepositoryTest`는 경로 우회, 쿼리 보존, 요청 소모·만료를 검사한다. `GoogleLoginIntegrationTest`는 모의 Google 제공자와 역할을 분리한 실제 PostgreSQL·JDBC 세션을 사용해 성공·취소·인증 실패, 세션 교체, 외부 주소 차단, 다른 브라우저·잘못된 state·이전 탭·재사용·만료, 기존 로그인 유지와 탈퇴 재인증을 검사한다. 실제 Google 계정과 프론트 화면에서의 복귀는 이번 검증에 포함하지 않는다.

2026-10-03 로컬 검증: `spotlessApply check bootJar` 성공. 단위 테스트 171개와 통합 테스트 366개, 총 537개가 실패·오류·건너뛰기 없이 통과했다. 이 중 로그인 복귀 단위 테스트는 33개이며 Google 로그인 통합 테스트는 기존 기능을 포함해 44개다.
