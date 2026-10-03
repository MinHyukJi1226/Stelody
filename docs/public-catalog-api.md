# 공개 곡·멤버 API

2026-09-30 구현. 모든 경로는 `/api/v1` 아래이며 아래 GET API는 로그인 없이 호출한다.

| 메서드·경로 | 응답 |
|---|---|
| GET `/songs` | 곡 카드의 커서 목록 |
| GET `/songs/recommendations` | 공개 곡에서 뽑은 랜덤 추천 카드 |
| GET `/songs/{id}` | 곡 카드, 대표 영상, 검색 노출 안내, 관련곡 최대 6개 |
| GET `/members` | 멤버 카드와 공개 곡 수의 커서 목록 |
| GET `/members/{id}` | 멤버 카드와 공식 채널 링크 |
| GET `/members/{id}/songs` | 해당 멤버가 확인된 참여자인 공개 곡 목록 |

## 곡 검색

| 파라미터 | 값·기본값 |
|---|---|
| `q` | 최대 200자. NFKC·공백·소문자 정규화. 제목·원곡 제목·별칭·멤버·외부 참여자·원곡 아티스트 검색 |
| `mode` | `BROWSE`, `SEARCH`. q 미전달은 BROWSE, 전달하면 SEARCH. SEARCH의 빈 검색어는 빈 목록 반환 |
| `type` | `ALL`(기본), `ORIGINAL`, `COVER` |
| `memberIds` | UUID를 쉼표 또는 반복 파라미터로 전달, 최대 20개. 여러 멤버 중 하나라도 참여하면 일치 |
| `year` | 1900~2100. 대표 영상 공개일의 KST 연도 |
| `collaboration` | boolean, 기본 false. true이면 확인된 멤버+외부 참여자 합계가 2명 이상인 곡만 조회 |
| `sort` | `LATEST`, `RELEVANCE`, `VIEWS`. 검색어가 있으면 RELEVANCE, 없으면 LATEST가 기본 |
| `size` | 1~50, 기본 20 |
| `cursor` | 이전 응답의 nextCursor. 프론트에서 내용을 해석하거나 생성하지 않음 |

빈 검색 화면과 전체 탐색을 구분한다. BROWSE에 비어 있지 않은 q를 전달하면 400이다. 관련도는 제목·별칭·원곡 제목/별칭의 정확 일치, 부분 일치, 멤버/아티스트 일치 순이다. 모든 정렬은 대표 영상 공개일 내림차순과 UUID 내림차순으로 동점을 해소한다. 조회수 미수집은 null이며 실제 0보다 뒤에 놓는다. `%`, `_`, `!`를 입력해도 문자 그대로 검색하며 SQL 값은 바인딩한다.

```text
GET /api/v1/songs?mode=BROWSE&size=20
GET /api/v1/songs?q=논브레스%20오블리주&sort=RELEVANCE
GET /api/v1/songs?type=COVER&year=2026&collaboration=true
GET /api/v1/songs?memberIds={memberA},{memberB}&sort=LATEST
```

목록 응답은 `{items, nextCursor, hasNext}`다. 끝이면 nextCursor=null, hasNext=false다. 페이지를 넘길 때 검색어·필터·정렬은 유지하며 조건을 바꾸면 cursor를 지운다. size만 변경할 수 있다. 공개 데이터의 변경에 따른 중복은 프론트에서 ID로 제거한다.

곡 카드 필드:

```json
{
  "id": "00000000-0000-0000-0000-000000000101",
  "title": "예시 곡",
  "type": "COVER",
  "publishedAt": "2026-01-01T00:00:00Z",
  "thumbnailUrl": null,
  "participants": [
    {"id": "00000000-0000-0000-0000-000000000001", "name": "예시 멤버", "kind": "MEMBER", "activityStatus": "ACTIVE"},
    {"id": "00000000-0000-0000-0000-000000000002", "name": "예시 외부 가수", "kind": "EXTERNAL_ARTIST", "activityStatus": null}
  ],
  "work": null,
  "collaboration": true,
  "isSpecialEvent": false,
  "specialEventLabel": null,
  "viewCount": null,
  "viewsObservedAt": null
}
```

원곡이 확인되면 work는 `{id, title, artists: [{id, name}]}`다. 원곡 미확인과 조회수 미수집을 임의 값으로 채우지 않는다. 외부 참여자는 멤버 목록에 포함하지 않고 곡의 participants에서 kind로 구분한다. 확인되지 않은 참여 관계는 표시·검색·집계에서 제외한다.

상세는 `{song, representativeVideo, searchHelp, relatedSongs}`다. representativeVideo는 `{id, youtubeId, kind, url, embeddable}`이며 재생이 안 되는 공개 영상도 원본 링크를 제공한다. searchHelp는 `{visibility, recommendedQuery, checkedAt}`다. 관련곡은 같은 작품의 다른 커버 또는 확인된 참여 멤버를 공유하는 다른 곡 중 최신 최대 6개다.

## 메인 랜덤 추천

2026-10-03 추가. GET `/api/v1/songs/recommendations?size=6`는 로그인 없이 호출한다. `size`는 1~20, 기본 6이며 응답은 `{ "items": [...] }`다. items에는 위 곡 카드와 같은 필드를 반환한다. 커서·개인화·추가 환경 설정은 없다.

검색 목록과 같은 공개 조건을 만족하는 전체 곡에서 선택한다. 최신 페이지나 조회수 순위로 후보를 제한하지 않으며 오리지널·커버·공동 참여곡·졸업 멤버의 곡을 포함한다. 결과 내 곡 ID는 중복되지 않는다. 후보가 요청 개수보다 적으면 있는 곡만, 공개 곡이 없으면 빈 items를 200으로 반환한다.

후보 순서를 무작위로 섞고, 이미 선택된 곡에서 확정된 멤버들이 등장한 횟수의 합이 가장 적은 곡부터 선택한다. 동점은 섞인 순서로 해소하고 최종 카드 순서도 섞는다. 공동 참여곡은 확정된 모든 스텔라이브 멤버를 반영하며 외부 가수와 미확정 관계는 반복 계산에 넣지 않는다. 멤버 다양성이 부족해도 다른 곡으로 요청 개수를 채운다. 같은 멤버 반복을 줄이는 방식이므로 모든 곡의 선택 확률이 같거나 멤버당 한 곡이 보장되는 것은 아니다. 별도 요청 사이에는 같은 곡이 다시 나올 수 있다.

후보 조회·선택된 카드·확정 참여자·완료된 조회수 게시본을 같은 REPEATABLE_READ 트랜잭션에서 읽는다. 미수집 조회수는 null, 실제 0은 0을 유지한다. YouTube API 호출이나 DB 쓰기는 하지 않는다. 잘못된 size는 400 `INVALID_CATALOG_QUERY`다.

현재 MVP 규모에서는 전체 후보의 곡 ID·확정 멤버 ID만 읽고, 선택된 곡의 카드와 관계를 일괄 조회한다. 후보 읽기와 메모리 사용은 전체 공개 곡 수에 비례한다. 카탈로그 확대 시 부하 측정에 따라 선택 방식을 조정한다.

역할이 분리된 PostgreSQL 통합 테스트로 비로그인 조회, 공개 조건, 중복 방지, 졸업 멤버·오리지널·공동 참여, 미확정 참여자 제외, 오래된 곡 포함, 후보 부족과 size 오류를 검증한다. 조회수 게시본·0/null과 기존 카드 형식을 유지한다. 1,000곡을 추가한 테스트에서 추천 6곡·20곡 모두 SELECT 5회였으며 곡별 관계 조회는 추가되지 않았다.

```sh
./gradlew test --tests '*SongRecommendationsTest' integrationTest --tests '*PublicCatalogIntegrationTest' --no-daemon
```

## 멤버 조회

멤버 목록의 status는 ALL(기본), ACTIVE, GRADUATED다. size·cursor는 곡 목록과 같은 제한을 사용한다. 활동 멤버가 먼저 나오고 정규화 이름, UUID 오름차순으로 정렬한다. 졸업 멤버와 공개 곡이 0개인 멤버도 조회 가능하다.

멤버 카드에는 id, name, generation(미입력 null), activityStatus, profileImageUrl, songCounts가 있다. songCounts는 `{total, originals, covers, collaborations}`이며 같은 공개 조건과 확인된 참여 관계로 계산한다. 공동 참여 수는 전체 수에 더하지 않는다. 멤버 상세는 `{member, channels}`다. 생일·데뷔일의 사용자 표시 정책은 아직 결정하지 않아 응답에서 제외한다.

멤버별 곡 목록은 type, collaboration, sort, size, cursor를 받는다. 지정한 멤버를 바꾸면 cursor도 지운다.

## 공개 조건과 조회수 집계

사이트 PUBLISHED, 대표 영상 PUBLIC, 공개일 확인, 확인된 스텔라이브 참여 멤버 1명 이상을 모두 만족해야 공개한다. 목록·상세·관련곡·멤버별 목록·곡 수에 같은 조건을 적용한다. 숨김·비공개·삭제·이용 불가·일부 공개·대표 영상 미지정 곡의 상세는 존재하지 않는 ID와 같은 404를 반환한다.

작품, 스텔라이브 개별 버전, 업로드를 각각 musical_work, song_entry, video로 관리한다. 대표 영상은 같은 곡에 속해야 하며 조회수를 다른 업로드와 합산하지 않는다. 오리지널은 공식 MV 우선, MV가 없으면 공식 음원 영상으로 처리한다. 커버는 공식 커버 영상을 기준으로 한다. 이번 단계는 지정된 대표를 조회하며, 대표 지정·변경과 자료 검수는 관리자 기능에서 구현한다.

조회수는 완료된 view_publication의 대표 영상별 published_video_view에서 읽는다. 향후 수집기는 새 집계를 구성하고 catalog_state의 포인터를 한 트랜잭션에서 변경한다. VIEWS 페이지 도중 게시된 집계가 바뀌면 409 `VIEW_PUBLICATION_CHANGED`로 새로고침을 요청한다. 한 API 요청은 REPEATABLE_READ 트랜잭션으로 포인터·카드·참여 정보를 동일 시점에 읽는다. 조회수 수집·차트·집계 게시 작업은 후속 범위이며 지금은 테스트 자료로 검증했다.

## 오류와 데이터 입력

오류는 기존 Problem 형식의 code, fieldErrors, traceId를 사용한다. 잘못된 UUID·필터·size·다른 조건에 재사용한 cursor는 400 `INVALID_CATALOG_QUERY`, 공개되지 않은 상세는 404 `CATALOG_NOT_FOUND`, 조회수 집계 변경은 위 409다. 내부 SQL·스택·입력 검색어는 오류 응답에 포함하지 않는다.

런타임 역할에는 새 업무 테이블 SELECT만 부여했다. 등록·편집 API와 수집기 계정 권한은 해당 기능에서 추가한다. 실제 초기 자료를 운영 DB에 자동 삽입하지 않는다. 테스트 데이터는 Testcontainers 내부에만 만들고 삭제한다. 관리자/수집기에서 제목·이름·별칭을 입력할 때 SearchText.normalize를 사용해 search_* 필드를 같이 저장해야 한다.

검색 필드에 pg_trgm GIN 인덱스를 구성했다. 확장이 이미 있으면 해당 스키마를 사용하고 없으면 app에 설치한다. PostgreSQL 기준 역할 분리 테스트와 V3에서 V5로 업그레이드하는 테스트로 검증한다. [PostgreSQL 공식 문서](https://www.postgresql.org/docs/17/pgtrgm.html)

## 검증 범위

비로그인 조회, 원문·별칭·폭·공백 정규화, 검색 중복, 멤버 OR 조건, 외부 공동 참여, 실제 공개일/KST 연도, 동일 시각 커서, 조회수 0/null·집계 버전, 영상 이용 불가와 수동 숨김 유지, 졸업·빈 멤버, DB 제약과 런타임 쓰기 차단을 PostgreSQL에서 검증한다.

1,000곡 테스트에서 20개와 50개 페이지 모두 SELECT 4회였다. 로컬 MockMvc로 예열 후 20회 검색한 표본 p95는 15ms였다. HTTP 네트워크·운영 호스팅·동시 10명 부하는 이 수치에 포함하지 않는다.

노래방 번호·외부 음원 링크·관리자 편집·즐겨찾기·플레이리스트·조회수 차트·프론트 화면·OpenAPI 자동 생성은 후속 기능이다.


## 원곡·개별 버전의 링크와 노래방 번호

노래 상세는 곡 자체의 `links`(platform/url), `karaoke`(provider/status/number)와 원곡의 `workResources`(workId/links/karaoke)를 각각 반환한다. TJ·KY는 UNKNOWN, NOT_LISTED, REGISTERED를 구분하며 미입력 사업자는 UNKNOWN이다. 번호는 문자열이고 앞자리 0을 보존한다. 원곡이 없으면 workResources는 null이다. 원곡의 링크·번호를 커버 버전의 정보로 복사하지 않는다.
