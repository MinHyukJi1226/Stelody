# 개인 플레이리스트 API

## 접근과 기본 규칙

모든 경로는 `/api/v1`로 시작하며 Stelody 로그인 세션이 필요하다. 소유자는 세션의 내부 회원 ID로 결정한다. 요청의 `ownerId`·`userId`를 소유자로 사용하지 않는다. 다른 회원의 목록·항목과 없는 리소스는 모두 404 `PLAYLIST_NOT_FOUND`로 처리한다. 공개 곡·멤버는 로그인 없이 조회할 수 있다.

변경 요청에는 `GET /auth/csrf`로 받은 헤더명·토큰과 세션 쿠키를 함께 보낸다. 개인 응답은 `Cache-Control: no-store`다. 프론트는 로그아웃·계정 전환 시 개인 데이터 캐시를 제거한다.

- 사용자당 기본 50개 목록: `PLAYLIST_LIMIT` / `stelody.playlists.limit`
- 목록당 기본 500곡: `PLAYLIST_ITEM_LIMIT` / `stelody.playlists.item-limit`
- 두 한도는 양의 정수로 설정한다. 낮춰도 기존 데이터를 자동 삭제하지 않는다. 기존 목록 수정·곡 제거·순서 변경은 계속 허용하고 신규 생성·추가는 한도 아래일 때 허용한다.
- 목록명은 앞뒤 Unicode 공백을 제거하고 1~50 Unicode 코드 포인트를 허용한다. 표시 원문과 내부 공백은 유지하며 같은 이름의 목록도 허용한다.
- 같은 곡은 한 목록에 한 번만 추가한다. 다른 목록에는 별도로 추가할 수 있다. 추가는 현재 공개 조건을 만족하는 곡에 한한다.
- 이용 불가 곡도 곡 수와 한도에 포함하며 제거·재정렬할 수 있다.

## 엔드포인트

| 메서드·경로 | 입력 | 성공 응답 |
|---|---|---|
| POST `/me/playlists` | `{name}` | 201 `Summary`, Location은 목록 상세 경로 |
| GET `/me/playlists` | `size`, `cursor` | `Page` |
| GET `/me/playlists/{id}` | — | `Summary` |
| PATCH `/me/playlists/{id}` | `{name, version}` | 200 변경 후 `Summary` |
| DELETE `/me/playlists/{id}` | 쿼리 `version` 필수 | 204, 항목도 함께 삭제 |
| GET `/me/playlists/{id}/items` | `size`, `cursor` | `Items` |
| POST `/me/playlists/{id}/items` | `{songId, version}` | 201 `{playlist: Summary, itemId}` |
| DELETE `/me/playlists/{id}/items/{itemId}` | 쿼리 `version` 필수 | 200 변경 후 `Summary` |
| PUT `/me/playlists/{id}/order` | `{itemIds: [...], version}` | 200 변경 후 `Summary` |

`itemId`는 플레이리스트의 항목 ID이며 곡의 `songId`와 다르다. 곡 추가는 마지막 위치에 넣는다. 곡 제거 시 뒤 항목의 위치를 당겨 0부터 연속적인 순서를 유지한다. 중복 곡 추가는 409 `PLAYLIST_SONG_ALREADY_EXISTS`로 거절하고 기존 항목·버전을 유지한다. 제거 대상 항목이 없거나 다른 목록에 속하면 404다.

## 버전과 동시 수정

생성 시 `version`은 0이다. 이후 이름 수정·곡 추가·곡 제거·순서 변경은 현재 버전이 필요하며 성공할 때 한 번 증가한다. 같은 이름으로 수정하거나 같은 순서를 제출해도 유효한 변경 요청이면 한 번 증가한다. 실패한 요청은 버전·시각·항목 변경을 모두 롤백한다.

오래된 버전과 동시 수정 충돌은 409 `PLAYLIST_CHANGED`다. 프론트는 목록을 다시 조회하고 사용자의 의도를 새 상태에서 확인한다. 응답을 받지 못한 요청을 같은 버전으로 무조건 재전송하지 않는다. 재조회로 실제 반영 여부를 확인한다.

JPA `@Version`으로 부모 행 UPDATE·DELETE의 버전을 검사한다. 곡 변경은 부모의 버전 UPDATE를 먼저 flush한다. 이 UPDATE가 획득한 행 잠금을 커밋까지 유지하여 같은 목록의 곡 변경을 직렬화한다. 신규 목록 생성은 회원 행을 잠가 같은 계정의 동시 생성이 한도를 넘지 않도록 한다.

## 페이지와 응답

두 목록 조회의 `size`는 기본 20, 최대 50이며 `nextCursor`를 그대로 다음 요청에 전달한다.

- 내 플레이리스트는 `createdAt DESC, id DESC`로 조회한다. 커서는 소유자·생성 시각·목록 ID에 묶인다. 이름 수정으로 생성 순서가 바뀌지 않는다.
- 곡 목록은 `position ASC`로 조회한다. 커서는 소유자·목록 ID·목록 버전·위치에 묶인다. 다음 페이지 전에 목록이 수정되면 409로 새로고침을 안내한다.
- 페이지마다 목록·곡 정보·수량은 같은 DB 스냅샷에서 읽는다. 다음 페이지는 새로운 스냅샷이다. 카탈로그의 노출·조회수 변경은 개인 목록 버전을 바꾸지 않는다.
- `totalCount`는 이용 불가 항목을 포함한 전체 저장 곡 수, `availableCount`는 현재 공개 조건을 만족한 저장 곡 수다. 페이지 크기와 무관하다. `Page.totalCount`는 내 플레이리스트 개수다.

`Summary`:

```json
{
  "id": "00000000-0000-0000-0000-000000000064",
  "name": "내 목록",
  "version": 2,
  "createdAt": "2026-10-01T00:00:00Z",
  "updatedAt": "2026-10-01T00:01:00Z",
  "totalCount": 2,
  "availableCount": 1
}
```

`Page`는 `{items: Summary[], nextCursor, hasNext, totalCount}`다. `Items`는 다음과 같다.

```json
{
  "version": 2,
  "items": [
    {
      "id": "00000000-0000-0000-0000-000000000065",
      "songId": "00000000-0000-0000-0000-000000000001",
      "position": 0,
      "addedAt": "2026-10-01T00:01:00Z",
      "available": false,
      "unavailableMessage": "현재 이용할 수 없는 곡",
      "song": null
    }
  ],
  "nextCursor": "이전 응답이 제공한 커서",
  "hasNext": true,
  "totalCount": 2,
  "availableCount": 1
}
```

이용 가능 항목은 `available: true`, `unavailableMessage: null`, `song`에 [공개 API의 Card](public-catalog-api.md)를 반환한다. 재생 정보는 공개 곡 상세에서 조회한다. 숨김·비공개·삭제 등 이용 불가 항목은 제목·썸네일·참여자·재생 링크를 반환하지 않는다. 저장 관계와 위치는 유지하며 다시 공개되면 정보가 복구된다.

## 전체 순서 교체

`itemIds`는 현재 목록의 **전체 항목 ID를 각각 한 번씩** 포함해야 한다. 일부 페이지의 항목, 곡 ID, 중복·누락·다른 목록의 항목은 400이다. 순서 변경 전에 프론트는 같은 목록 버전의 전체 항목 ID를 확보해야 한다. 빈 목록에는 빈 배열을 보낸다.

```json
{
  "version": 2,
  "itemIds": [
    "00000000-0000-0000-0000-000000000066",
    "00000000-0000-0000-0000-000000000065"
  ]
}
```

부모 버전을 검사한 뒤 현재 항목 집합과 요청을 비교하고 위치 전체를 한 SQL로 변경한다. `(playlist_id, position)` 유일성 제약은 `DEFERRABLE INITIALLY DEFERRED`로 설정해 교환 중 임시 충돌을 허용하며 커밋 결과에서 중복을 금지한다. 유효하지 않은 요청은 버전 갱신까지 함께 롤백한다.

## 오류

| 상태 | code | 조건 |
|---|---|---|
| 400 | INVALID_PLAYLIST_REQUEST | 잘못된 이름·UUID·JSON·버전·size·cursor·항목 집합 |
| 401 | AUTHENTICATION_REQUIRED / SESSION_EXPIRED | 비로그인·만료·정지·삭제된 계정 |
| 403 | ACCESS_DENIED | CSRF 누락·불일치, 허용하지 않은 메서드 |
| 404 | PLAYLIST_NOT_FOUND | 없는 목록·항목 또는 다른 회원의 리소스 |
| 404 | CATALOG_NOT_FOUND | 신규 추가 대상이 없거나 공개 조건 불충족 |
| 409 | PLAYLIST_CHANGED | 오래된 버전·동시 수정 충돌·곡 페이지의 버전 변경 |
| 409 | PLAYLIST_LIMIT_REACHED | 사용자 목록 한도 |
| 409 | PLAYLIST_ITEM_LIMIT_REACHED | 목록 곡 한도 |
| 409 | PLAYLIST_SONG_ALREADY_EXISTS | 같은 목록의 중복 곡 |

Spring Security는 인증 전에 CSRF를 검사하므로 변경 요청에 유효한 토큰이 없으면 비로그인 상태에서도 403일 수 있다. 오류는 기존 Problem 형식의 `code`, `fieldErrors`, `traceId`를 제공한다.

## DB와 검증

Flyway V7가 플레이리스트·항목, FK·중복 곡·위치 제약·조회 인덱스를 생성한다. 실행 역할에는 두 테이블의 SELECT·INSERT·UPDATE·DELETE만 부여한다. 회원 삭제는 목록과 항목을 함께 삭제하고, 목록 삭제는 해당 항목을 삭제한다. 일반 생성·수정·삭제는 JPA, 조회·집계와 전체 순서/위치 이동은 JdbcClient를 사용한다. SQL 입력값은 파라미터로 바인딩한다.

```sh
# backend 디렉토리에서, Docker 실행 필요
./gradlew integrationTest --tests '*PlaylistIntegrationTest' --tests '*PlaylistMigrationIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

역할이 분리된 PostgreSQL, JDBC 세션과 실제 CSRF 토큰으로 검증한다. 계정 분리, 버전 충돌·롤백, 50개·500곡 경계의 동시 요청, 자리표시자, 커서, 500곡 전체 순서 교체, DB 권한·제약과 V6 업그레이드를 포함한다. 테스트 자료는 Testcontainers에만 등록한다. 프론트 화면과 YouTube 내보내기는 후속 단계다.
