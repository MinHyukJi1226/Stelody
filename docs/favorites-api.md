# 즐겨찾기 저장·해제 API

모든 경로는 `/api/v1`로 시작하며 Google 로그인 후 생성된 Stelody 세션 쿠키가 필요하다. 소유자는 세션의 내부 회원 ID로 결정하고 요청의 `ownerId`·`userId`는 소유자 지정에 사용하지 않는다. 개인 응답은 `Cache-Control: no-store`다. 공개 곡·멤버는 비로그인 조회를 유지한다.

| 메서드·경로 | 응답 | 동작 |
|---|---|---|
| PUT `/me/favorites/{songId}` | 204 | 공개 곡 저장. 기존 항목의 저장 시각 유지 |
| DELETE `/me/favorites/{songId}` | 204 | 내 저장 기록 해제. 미등록 항목도 성공 |
| GET `/me/favorites/{songId}` | `{songId, favorited}` | 내 저장 여부. 곡의 정보·공개 여부는 반환하지 않음 |

변경 요청은 `GET /auth/csrf`에서 받은 헤더명·토큰과 세션 쿠키를 함께 보낸다. 저장·해제는 멱등하며 토글 API를 사용하지 않는다.

신규 저장은 공개 곡에만 허용한다. 이후 이용 불가 상태가 되어도 기존 기록을 유지하고 PUT 재시도와 DELETE는 허용한다. 해제 후 재등록은 다시 공개된 곡에만 허용한다.

## 한도와 오류

사용자당 기본 5,000곡이며 이용 불가 기록도 포함한다. `FAVORITE_LIMIT` 또는 `stelody.favorites.limit`는 양의 정수로 설정한다. 한도를 낮춰도 기존 항목을 자동 삭제하지 않으며 기존 PUT·DELETE는 계속 허용한다.

| 상태 | code | 조건 |
|---|---|---|
| 400 | INVALID_FAVORITE_QUERY | 잘못된 UUID |
| 401 | AUTHENTICATION_REQUIRED / SESSION_EXPIRED | 비로그인·만료·정지·삭제된 계정 |
| 403 | ACCESS_DENIED | CSRF 누락·불일치, 허용하지 않은 메서드 |
| 404 | CATALOG_NOT_FOUND | 신규 저장 대상이 없거나 공개 조건 불충족 |
| 409 | FAVORITE_LIMIT_REACHED | 신규 저장 시 사용자 한도 도달 |

Spring Security는 인증 전에 CSRF를 검사하므로 변경 요청에 유효한 CSRF 토큰이 없으면 비로그인 상태에서도 403을 반환할 수 있다. 오류는 기존 Problem 형식의 `code`, `fieldErrors`, `traceId`를 제공한다.

## 저장과 검증

Flyway V6가 `(user_id, song_id)` 기본 키, 저장 순서 인덱스, FK를 생성한다. 회원 삭제 시 저장 기록을 함께 삭제한다. 실행 역할은 SELECT·INSERT·DELETE만 사용한다. 저장·해제·존재 검사는 JPA, 회원 행 잠금과 공개 여부 검사는 JdbcClient를 사용한다. 같은 회원의 저장·해제는 회원 행 잠금을 커밋까지 유지하여 중복과 한도 초과를 방지한다.

```sh
# backend 디렉토리에서, Docker 실행 필요
./gradlew integrationTest --tests '*FavoriteIntegrationTest' --tests '*FavoriteMigrationIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

역할이 분리된 PostgreSQL, JDBC 로그인 세션과 실제 CSRF 토큰으로 중복 저장·해제, 계정 분리, 한도·동시 요청, 이용 불가 곡의 저장 유지, DB 권한·제약·마이그레이션을 검증한다. 테스트 자료는 Testcontainers에만 등록한다. 내 목록 조회는 다음 작업에서 추가한다.
