# 수집 분류 규칙 관리 API

2026-10-03. 모든 경로는 `/api/v1/admin` 아래이며 ADMIN 세션이 필요하다. 응답은 `Cache-Control: no-store`, 변경·미리보기 요청은 기존 CSRF 토큰을 요구한다. 익명 401, 일반 회원 403, 버전 충돌 409다. 웹과 별도 수집기는 서로 다른 PostgreSQL 역할을 사용한다.

## 분류 규칙

| 메서드·경로 | 동작 |
| --- | --- |
| GET `/collection-rules` | 현재 `version`, `configuration` 조회 |
| POST `/collection-rules/preview` | 제안한 규칙으로 샘플 제목 판정, DB·YouTube 변경 없음 |
| PUT `/collection-rules` | 현재 버전을 조건으로 규칙 저장, 변경 전후·관리자·사유 기록 |
| GET `/collection-rules/audit?page=0&size=20` | 변경 이력, size 1~50 |

`configuration`은 `cover`, `original`, `exclude` 배열이다. 각 배열은 최대 30개, 각 원소는 `{ "text": "cover", "match": "WORD" }` 형식이다. `WORD`는 앞뒤가 유니코드 문자·숫자가 아닌 경우만 일치한다. `PHRASE`는 문구가 포함되면 일치한다. 정규식은 지원하지 않는다. NFKC·소문자·공백 정규화 후 빈 문구와 같은 배열의 중복을 거절한다. 문구는 입력·정규화 결과 모두 1~60자다. 빈 배열은 해당 제안을 끈다.

초기 설정은 기존 Cover/Covered by/歌ってみた, original/MV/오리지널, shorts/clip/clips/livestream/[클립]/[방송]/[다시보기] 규칙을 유지한다. 제외 → 커버 → 오리지널 순서이며, 비공개·미등록 상태는 DEFERRED다. 영상 길이로 Shorts를 추정하지 않는다. 곡 생성·참여자 추정·공개는 자동으로 수행하지 않는다.

미리보기 요청 예시:

```json
{
  "version": 0,
  "configuration": {
    "cover": [{"text": "cover", "match": "WORD"}],
    "original": [],
    "exclude": [{"text": "shorts", "match": "WORD"}]
  },
  "samples": [
    {"title": "Test Cover #Shorts", "availability": "PUBLIC"},
    {"title": "Discovery", "availability": "PUBLIC"}
  ]
}
```

샘플은 1~20개, 제목은 1~500자, availability는 PUBLIC/UNLISTED/PRIVATE/DELETED/UNAVAILABLE다. 응답은 `baseVersion`과 입력 순서의 `decisions`다. 미리보기는 분류를 허용했을 때의 예상 결과이며 실제 수집 활성화 상태를 바꾸지 않는다. 미리보기의 `version`은 `preview:<현재 버전>`이다. 저장 요청은 `samples`를 빼고 1~500자 `reason`을 넣는다. 저장에 미리보기 실행 토큰을 요구하지는 않으므로 관리자 화면에서 먼저 샘플을 확인하도록 구성한다.

수집기는 한 실행 시작 시 설정을 읽어 실행 전체에서 고정한다. 변경은 다음 실행부터 적용된다. 탐색 실행 이력과 각 후보에 `title-v2:<버전>`을 남긴다. 이전 실행의 버전 열은 null이다. 정책 비허용 실행은 기존 `title-v1`로 `CLASSIFICATION_DISABLED`를 남긴다. 분류를 허용했는데 DB 규칙을 읽을 수 없으면 실패하며 기본 규칙으로 대체하지 않는다.

규칙 저장은 기존 후보를 일괄 재판정하지 않는다. 이후 실제 재관측되는 PENDING 후보만 기존 탐색 규칙에 따라 갱신된다. IGNORED/REGISTERED와 관리자가 입력한 곡은 그대로 유지한다. 채널 허용 목록은 기존 `/channels` 관리 API의 GROUP/MEMBER·`collectionEnabled`를 사용한다.

## 적용 설정

웹 마이그레이션 계정으로 V16을 적용하고, 제목 분류를 켜기 전에 스키마 소유자로 [규칙 읽기 권한](../infra/sql/collection-rule-grants.sql)을 수집 역할에 추가 적용한다. `DISCOVERY_CLASSIFICATION_ALLOWED`는 정책 확인 전 false를 유지한다. 규칙 편집·미리보기는 이 설정을 활성화하지 않는다. 기존 곡·후보는 일괄 재판정하지 않는다.
