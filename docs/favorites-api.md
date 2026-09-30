# 즐겨찾기 API

## 접근과 요청

모든 경로는 `/api/v1`로 시작하며 Google 로그인 후 생성된 Stelody 세션 쿠키가 필요하다. 소유자는 세션의 내부 회원 ID로 결정한다. 요청의 `ownerId`·`userId`로 다른 계정의 목록을 조회하거나 변경할 수 없다. 개인 응답은 `Cache-Control: no-store`이며 프론트는 로그아웃·계정 전환 시 개인 목록 캐시를 비운다. 공개 곡·멤버 조회의 비로그인 접근은 유지한다.

| 메서드·경로 | 성공 응답 | 동작 |
|---|---|---|
| PUT `/me/favorites/{songId}` | 204 | 공개 곡 저장. 이미 저장한 곡이면 저장 시각을 유지한다 |
| DELETE `/me/favorites/{songId}` | 204 | 내 저장 기록 해제. 저장하지 않은 곡도 성공한다 |
| GET `/me/favorites/{songId}` | `{songId, favorited}` | 해당 UUID에 대한 내 저장 여부. 숨겨진 곡의 정보나 존재 여부는 제공하지 않는다 |
| GET `/me/favorites` | 아래 페이지 응답 | 최신 저장 순으로 내 목록 조회 |

`PUT`, `DELETE`는 `GET /auth/csrf`에서 받은 `headerName`과 `token`을 헤더로 보낸다. 쿠키도 함께 전송한다. 토글 API를 사용하지 않으며, 실패한 저장 요청을 재시도해도 중복 등록하거나 순서가 바뀌지 않는다.

## 목록 계약

- 쿼리: `size` 기본 20, 허용 1~50. `cursor`는 이전 응답의 `nextCursor`를 그대로 사용한다.
- 정렬: `savedAt DESC, songId DESC`. 커서는 계정·저장 시각·곡 ID를 포함하며 다른 계정의 커서는 400으로 거절한다. 커서 자체는 인증 수단이 아니며 모든 DB 조회에 세션 소유자 조건을 별도로 적용한다.
- 각 응답의 전체 수·이용 가능 수·목록·곡 정보는 같은 DB 스냅샷에서 읽는다. 다음 페이지는 새로운 스냅샷이다. 스크롤 중 추가한 곡은 첫 페이지 새로고침으로 확인하고, 프론트는 곡 ID 중복을 제거한다.
- `totalCount`는 이용 불가 곡을 포함한 저장 수, `availableCount`는 현재 [공개 API](public-catalog-api.md)의 노출 조건을 만족한 저장 수다. 페이지 크기와 무관한 전체 수다. 임베드 재생이 허용되지 않더라도 공개 영상이면 이용 가능한 곡에 포함한다.

```json
{
  "items": [
    {
      "songId": "00000000-0000-0000-0000-000000000065",
      "savedAt": "2026-09-30T01:00:00.123456Z",
      "available": true,
      "unavailableMessage": null,
      "song": { "id": "00000000-0000-0000-0000-000000000065", "title": "예시 곡" }
    },
    {
      "songId": "00000000-0000-0000-0000-000000000066",
      "savedAt": "2026-09-29T01:00:00Z",
      "available": false,
      "unavailableMessage": "현재 이용할 수 없는 곡",
      "song": null
    }
  ],
  "nextCursor": null,
  "hasNext": false,
  "totalCount": 2,
  "availableCount": 1
}
```

위 예시의 `song`은 일부 필드만 표시했다. 실제 응답은 공개 API의 `Card` 전체 필드다. 재생 정보는 공개 곡 상세에서 조회한다. 이용 불가 항목의 제목·썸네일·참여자·재생 링크는 반환하지 않는다. 곡 숨김, 대표 영상의 비공개·삭제·이용 불가 등 공개 조건 변경 시 저장 관계는 유지되며 해제할 수 있다. 공개 상태로 돌아오면 곡 정보가 다시 표시된다.

신규 저장은 공개 곡에만 허용한다. 이미 저장한 곡은 이후 이용 불가 상태가 되어도 PUT 재시도를 성공 처리한다. 해제 후에는 다시 공개되기 전까지 신규 저장할 수 없다.

## 한도와 오류

사용자당 기본 5,000곡. 이용 불가 저장 기록도 한도에 포함한다. `FAVORITE_LIMIT` 환경변수 또는 `stelody.favorites.limit`로 양의 정수 설정을 사용한다. 설정을 낮춰 기존 저장 수가 한도보다 많아져도 자동 삭제하지 않는다. 해제와 기존 항목 PUT은 계속 허용하고 신규 저장은 한도 아래가 될 때까지 거절한다.

| 상태 | code | 조건 |
|---|---|---|
| 400 | INVALID_FAVORITE_QUERY | 잘못된 UUID·size·cursor 또는 다른 계정의 커서 |
| 401 | AUTHENTICATION_REQUIRED / SESSION_EXPIRED | 비로그인·만료·정지·삭제된 계정 |
| 403 | ACCESS_DENIED | CSRF 토큰 누락·불일치, 허용하지 않은 메서드 |
| 404 | CATALOG_NOT_FOUND | 신규 저장 대상이 없거나 공개 조건 불충족 |
| 409 | FAVORITE_LIMIT_REACHED | 신규 저장 시 사용자 한도 도달 |

Spring Security는 인증 전에 CSRF를 검사하므로 변경 요청에 유효한 CSRF 토큰이 없으면 비로그인 상태에서도 403을 반환할 수 있다. 오류는 기존 Problem 형식의 `code`, `fieldErrors`, `traceId`를 제공한다.

## 저장과 검증

Flyway V6가 `(user_id, song_id)` 기본 키, 저장 순서 인덱스, FK를 생성한다. 회원 삭제 시 저장 기록은 함께 삭제한다. 곡은 숨김/영상 상태 변경으로 관리하며 저장 관계가 있는 곡의 물리 삭제는 FK가 차단한다. 실행 역할은 이 테이블의 SELECT·INSERT·DELETE만 사용한다.

일반 저장·해제·존재 검사는 JPA, 목록과 공개 정보 집계는 JdbcClient의 명시적인 SQL을 사용한다. 저장·해제는 회원 행을 잠그고 커밋까지 유지한다. 같은 회원의 동시 요청에서 중복과 한도 초과를 방지하며, 다른 회원은 별도 행을 사용한다. 목록 곡·참여자·원곡 정보는 페이지 단위로 조회한다.

```sh
# backend 디렉토리에서, Docker 실행 필요
./gradlew integrationTest --tests '*FavoriteIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

역할이 분리된 PostgreSQL, JDBC 로그인 세션, 세션별 실제 CSRF 토큰으로 검증한다. 중복 저장·해제, 계정 분리, 5,000곡 한도의 동시 요청, 자리표시자, 커서, 권한·제약·회원 삭제를 포함한다. 테스트용 곡은 Testcontainers에만 등록하며 실제 서비스 DB에는 넣지 않는다. 프론트 화면과 실제 곡 등록은 후속 단계다.
