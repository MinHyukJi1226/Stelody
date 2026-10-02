# 관리자 카탈로그 편집·후보 등록

## 범위와 접근

멤버·아티스트·원곡·채널·곡을 입력하고 수집 후보를 기존 곡에 연결한다. 곡을 먼저 DRAFT로 만든 뒤 영상을 연결하고 대표 영상을 지정하여 PUBLISHED로 공개한다. 같은 녹음의 MV·음원·재업로드는 관리자가 같은 곡에 연결하며 제목만으로 자동 병합하지 않는다. 실제 콘텐츠는 추정하여 등록하지 않았다.

모든 `/api/v1/admin` 요청은 현재 DB 역할이 ADMIN인 활성 로그인 세션이 필요하다. 비로그인 401, 일반 회원 403이며, POST·PUT에는 `/api/v1/auth/csrf`에서 받은 토큰이 필요하다. 응답은 `Cache-Control: no-store`다. 공개 곡·멤버 조회는 계속 로그인 없이 이용한다.

## 기본 편집 API

| 대상 | 목록·상세 | 생성 | 전체 편집 |
|---|---|---|---|
| 멤버 | GET `/api/v1/admin/members`, `/{id}` | POST 같은 목록 경로 | PUT `/{id}` |
| 아티스트 | GET `/api/v1/admin/artists`, `/{id}` | POST 같은 목록 경로 | PUT `/{id}` |
| 원곡 | GET `/api/v1/admin/works`, `/{id}` | POST 같은 목록 경로 | PUT `/{id}` |
| 채널 | GET `/api/v1/admin/channels`, `/{id}` | POST 같은 목록 경로 | PUT `/{id}` |
| 곡 | GET `/api/v1/admin/songs`, `/{id}` | POST 같은 목록 경로 | PUT `/{id}` |

목록은 `q`(최대 200자), `page`(0부터, 최대 10000), `size`(기본 20, 최대 50)를 받는다. 이름·제목·별칭을 검색하며 채널은 이름·YouTube 채널 ID를 검색한다. `%`·`_`는 검색 문자로 취급한다. `items/page/size/hasNext`를 반환한다. 편집할 때에는 상세 응답을 사용한다. 목록은 관계를 개별 조회하지 않는 요약 projection이다.

입력에는 항상 `version`(생성은 0, 수정은 현재 버전)과 `reason`(공백 제외 1~500자)이 필요하다. 누락·null·음수 버전은 400, 현재 값과 다른 버전 및 실제 동시 수정은 409 `CATALOG_VERSION_CONFLICT`다. 동일한 내용을 저장해도 편집 버전과 이력이 갱신된다. 관계·별칭·링크·번호 배열은 반드시 보내며 전체를 교체한다. 부모의 JPA `@Version` 갱신과 관계 교체·이력 삽입은 같은 트랜잭션이다.

멤버·아티스트·원곡·곡 저장 응답은 `item`과 `possibleDuplicateIds`다. 정규화한 이름/제목이 같은 기존 항목의 ID를 최대 20개 알려주며 자동 병합하거나 동일 제목을 금지하지 않는다. 채널은 리소스 DTO를 바로 반환한다. 생성 응답은 201, 수정은 200이다.

### 편집 필드

- 멤버: `name`, `generation`(null 또는 1~32767), `activityStatus`(ACTIVE/GRADUATED), `profileImageUrl`, `debutDate`, `birthdayMonth/birthdayDay`, `aliases`. 월일은 둘 다 null 또는 실제 날짜이며 2월 29일을 허용한다. 이름은 100자, 별칭은 각각 200자·최대 50개다. 졸업 변경으로 기존 곡·주소를 삭제하지 않는다. 이미지 권한·출처를 확인한 자료를 운영자가 입력한다.
- 아티스트: `name`(200자), `aliases`(각 200자·최대 50개). 원곡 가수 및 곡의 외부 참여자 참조에 사용한다.
- 원곡: `title`(300자), `aliases`(각 300자·최대 50개), `artistIds`(최대 100개, 순서 유지), `links`, `karaoke`.
- 채널: `youtubeId`(UC 형식 24자), `name`, `channelType`(GROUP/MEMBER/EXTERNAL), `memberId`, `collectionEnabled`. 멤버 연결은 MEMBER 채널에만 허용한다. 기존 채널 ID와 유형은 바꾸지 못하며 새 채널을 별도로 등록한다. EXTERNAL 채널은 수집을 활성화할 수 없다. 수집 여부 변경은 다음 작업 실행에서 반영한다.
- 곡: `title`, `type`(ORIGINAL/COVER), `workId`(미확인 시 null), `visibility`(DRAFT/PUBLISHED/HIDDEN), `representativeVideoId`, `aliases`, `memberIds`, `externalArtistIds`, `isSpecialEvent`, `specialEventLabel`, `searchVisibility`(UNCHECKED/NORMAL/DIFFICULT), `recommendedSearchQuery`, `links`, `karaoke`.

곡의 참여 ID 배열은 각각 최대 100개이며 중복을 거절한다. 연결한 참여자는 관리자가 확인한 것으로 기록한다. 그룹 채널 소유자나 제목으로 참여자를 추정하지 않는다. 외부 참여자는 공동 참여 판정에 포함한다. `isSpecialEvent=true`이면 1~60자 문구가 필요하고 false이면 문구를 null로 정리한다. 고정된 이벤트 유형은 없다. DIFFICULT에는 권장 검색어가 필요하다. 확인한 검색 상태의 시각은 서버가 기록하며, 무관한 편집에서는 기존 확인 시각을 유지한다. 상태를 UNCHECKED로 바꾸면 시각·권장 검색어를 비운다.

등록할 제목·별칭은 NFKC·공백·소문자 정규화를 검색 필드에 적용한다. 정규화 후 중복 별칭 및 DB 제한보다 길어진 값도 거절한다. 표시 원문은 공백 제거 외에 보존한다.

### 링크·노래방

`links`는 최대 20개이며 각각 `platform`, `url`, `sourceUrl`을 받는다. 공개 상세는 platform/url만 제공한다. 사용자 정보가 포함된 URL, HTTP, javascript, 로컬 주소, 숫자 IP, 비표준 포트, fragment는 허용하지 않는다. 링크는 서버에서 요청하지 않는다.

`karaoke`는 사업자당 한 항목, 최대 2개다. `provider`는 TJ/KY, `status`는 UNKNOWN/NOT_LISTED/REGISTERED다. REGISTERED에는 `number`(문자열 최대 40자)와 `sourceUrl`이 필요하다. 앞자리 0을 보존한다. NOT_LISTED에는 확인 출처가 필요하며 번호는 null이다. UNKNOWN도 번호는 null이다. 등록·미수록 확인 시각은 서버가 기록한다.

원곡과 해당 스텔라이브 버전의 링크·번호는 각각 저장한다. 공개 상세의 `links/karaoke`는 곡 자체의 자료이며 `workResources`는 원곡 자료다. 등록하지 않은 TJ/KY 항목은 공개 응답에서 UNKNOWN으로 반환한다. 서로의 번호·링크를 복사하거나 합산하지 않는다.

## 후보를 곡에 연결

POST `/api/v1/admin/reviews/{id}/registration`

```json
{
  "version": 0,
  "songId": "00000000-0000-0000-0000-000000000001",
  "songVersion": 0,
  "kind": "OFFICIAL_COVER",
  "reason": "공식 커버와 참여자를 확인함"
}
```

PENDING 후보만 등록할 수 있다. 허용된 공식 GROUP/MEMBER 채널에서 수집한 공개 영상이어야 하며, 공개 시각·제목이 있고 원본 관측이 30일 이내여야 한다. 예약 공개·비공개·만료·무시 후보 및 수집이 비활성인 채널은 409로 거절한다. source를 다른 채널로 바꾸거나 회원 입력을 YouTube 관측값으로 취급하지 않는다.

곡 버전과 후보 버전을 모두 검사한다. 초기 등록은 관측 원본과 영상 ID를 복사하고, 후보를 REGISTERED로 바꾸어 `registeredVideoId`를 기록한다. 곡 버전도 증가한다. 같은 영상 ID는 한 곡에만 연결되며 중복 등록은 409다. 등록된 후보는 무시·복원 API로 다시 PENDING으로 만들 수 없다. 목록 `status=REGISTERED`에서 등록 결과를 찾을 수 있다.

후보에 임베드 가능 여부가 없는 기존 자료는 안전하게 `embeddable=false`로 시작한다. 다음 등록 영상 수집에서 실제 값을 갱신하며 원본 YouTube 링크 이용은 가능하다. 등록 응답은 영상 DTO이며 대표 영상 지정·공개를 자동으로 수행하지 않는다. 등록 실패·수집 충돌·감사 기록 실패 시 영상 생성, 후보 상태, 곡 버전을 모두 롤백한다.

영상 URL 입력은 POST `/api/v1/admin/songs/{id}/videos`에 `version`(곡), `reviewVersion`, `videoUrl`, `kind`, `reason`을 보낸다. 11자 ID·HTTPS youtu.be·YouTube watch/embed URL을 정규화하고 이미 수집한 후보에 대해 같은 등록 절차를 수행한다. 새 URL을 입력해 즉석으로 YouTube를 호출하거나 출처 미확인 영상을 생성하지 않는다. 관측 후보가 없으면 409 `VIDEO_REQUIRES_OBSERVATION`이다.

## 영상 편집과 공개 기준

PUT `/api/v1/admin/songs/{id}/videos/{videoId}?songVersion={현재 곡 버전}`

본문은 영상의 `version`, `kind`, `publishedAt`, `thumbnailUrl`, `reason`이다. kind는 OFFICIAL_MV/OFFICIAL_COVER/AUDIO/REUPLOAD/OTHER다. 날짜·썸네일 null은 수동 override 해제다. 수집 원본·이용 상태·조회수는 요청으로 수정할 수 없다. 영상 편집도 곡 버전을 증가시킨다.

PUBLISHED 저장에는 확인된 스텔라이브 멤버가 최소 한 명 있고, 해당 곡에 속한 공개 대표 영상과 공개 시각이 필요하다. 대표 원본의 관측이 만료됐으면 재수집을 요구한다. 커버는 OFFICIAL_COVER가 대표이고, 오리지널은 OFFICIAL_MV가 우선이다. 연결된 공식 MV가 없을 때 AUDIO를 허용한다. 이용 불가한 MV의 자동 대체는 구현하지 않는다. 원곡·별칭·음원 링크·노래방 번호 누락은 공개를 막지 않는다. 관리자 상세의 `missingFields`는 핵심 연결·검색 확인의 미완성 항목을 별도 표시한다.

이미 공개한 음원 곡에 공식 MV를 추가하는 경우 먼저 DRAFT/HIDDEN으로 전환하고, MV 연결 및 대표 지정 후 공개한다. 등록 과정에서 대표 영상을 자동 교체하지 않는다. 대표 영상의 관측·조회수 이력은 영상별로 유지하며 다른 영상 기록을 이어 붙이지 않는다.

곡 삭제는 HIDDEN 편집으로 처리한다. 물리 삭제·멤버/원곡 삭제·자동 병합 API는 제공하지 않는다. 숨겨도 즐겨찾기·개인 플레이리스트의 기존 관계는 유지한다. 수집기의 재공개 관측이 관리자 HIDDEN, 표시 제목·별칭·특별 문구·검색 확인·참여자·수동 날짜·썸네일을 덮어쓰지 않는다.

## 이력과 오류

GET `/api/v1/admin/{members|artists|works|songs|channels|videos|reviews}/{id}/audit`는 관리자에게 `items/page/size/hasNext`를 반환한다. page는 0~10000, size는 1~50(기본 20)이며 최신 이력부터 조회한다. 대상이 없으면 404, 대상은 있고 이력이 없으면 빈 items를 반환하며 응답은 no-store다. 대상·전후 편집 값·현재 세션의 관리자·사유·시각을 기록한다.

`reviews/{id}/audit`는 catalog_audit에 기록한 후보 등록 전후 상태와 연결된 videoId/songId를 조회한다. `videos/{id}/audit`에서 영상 종류·수동 날짜·썸네일의 편집 전후를 조회한다. 영상 원본 metadata는 이력에 복제하지 않는다. 등록 이력은 후보 및 곡에 함께 남기며 감사 저장 실패 시 편집도 롤백한다. 수집 원본의 30일 만료 정책과 수동 편집 이력은 구분한다.

기본 오류는 400 INVALID_CATALOG_REQUEST, 404 CATALOG_RESOURCE_NOT_FOUND, 409 CATALOG_VERSION_CONFLICT다. 잘못된 참조·공개 조건·대표 기준은 별도 code로 알리고 SQL·내부 예외를 반환하지 않는다. DB unique/FK 충돌은 409 CATALOG_DATA_CONFLICT다.

## 운영용 관리자 지정

V11의 역할 권한 분리와 `--admin-account` 명령, 운영 DB 계정 준비·감사·철회 절차는 [운영용 관리자 지정](admin-accounts.md)을 참고한다. 카탈로그 편집 API에는 V12까지 적용해야 한다.

## 로컬 검증 결과

2026-10-02. Java 21·역할 분리 PostgreSQL 17.6·Testcontainers에서 `GRADLE_USER_HOME=/tmp/stelody-gradle ./gradlew spotlessApply check bootJar --no-daemon` 성공. 단위 84개·통합 187개, 총 271개가 실패·오류·건너뜀 없이 통과했다.

검증에는 비로그인/일반 회원/CSRF/역할 철회, 별칭 검색·중복 경고, 후보 등록·등록 상태 복원 차단, 공개·숨김·복원, MV 우선과 음원, 원곡 미확인·외부 참여자·링크·번호의 분리, 실제 동시 편집의 한 요청만 성공, 수집 갱신 충돌 롤백, 수동 필드·HIDDEN 보존, 감사 실패 롤백, 중복 채널 409, 기존 V10 업그레이드와 권한 분리가 포함된다. 기존 공개 목록의 1,000곡 관계 쿼리 수 검증도 유지한다.

실제 로컬 DB·계정·550개 후보의 상태는 변경하지 않았다. 관리자 프론트 화면·실제 콘텐츠 검수·등록·운영 설정 적용은 별도로 진행해야 한다.

2026-10-03 PR #9 리뷰 반영: 영상·후보 이력 조회 경로가 빠져 관리자 요청도 403을 반환하는 현상을 회귀 테스트에서 재현했다. 조회 경로·ADMIN 허용 목록·대상 확인을 추가한 뒤 동일 검증 명령으로 단위 84개·통합 191개, 총 275개가 실패·오류·건너뜀 없이 통과했다. 후보 등록 전후 상태와 영상·곡 연결, 영상 수동 편집 전후 값·페이지 처리·빈 이력·없는 대상·잘못된 페이지·일반 회원 및 역할 철회 차단·수집 원본 제외를 검증했다.
