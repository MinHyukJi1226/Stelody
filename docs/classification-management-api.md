# 수집 분류 규칙과 기념일 검토 API

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

## 기념일 후보

기준은 **확정된 참여 멤버**와 **대표 영상의 KST 공개일**이다. 공개일은 관리자 수정값을 우선하고, 없으면 YouTube 관측값을 쓴다. 공개 영상이며 원본 관측이 30일 이내이고 공개 시각이 미래가 아닐 때만 판정한다. 대표 영상·참여 멤버·날짜가 없으면 후보를 만들지 않는다. 채널 소유자나 그룹 전체를 참여자로 추정하지 않는다. 곡의 DRAFT/PUBLISHED/HIDDEN 상태와 후보 판정은 독립적이다.

- 생일은 월·일 일치. 2월 29일은 해당 날짜가 존재하는 윤년에만 일치한다.
- 데뷔 기념일은 데뷔일 이후의 같은 월·일. 데뷔 당일이나 데뷔 전 날짜는 제외한다.
- 제목에 기념일 문구가 없어도 날짜만으로 후보를 만든다.
- 한 곡에 후보 하나를 만들고, 여러 멤버·날짜 근거를 모두 담는다.
- 근거의 BIRTHDAY_DATE_MATCH/DEBUT_DATE_MATCH는 검토 사유다. 공개 특별 목적 유형 enum이나 라벨을 자동 생성하지 않는다.

| 메서드·경로 | 동작 |
| --- | --- |
| POST `/songs/{id}/special-event-review` | 해당 곡 후보를 즉시 생성·갱신, 일치하지 않으면 candidateId=null |
| GET `/special-event-reviews?status=PENDING&page=0&size=20` | 후보 조회, 상태 생략 시 전체 |
| GET `/special-event-reviews/{id}` | 근거·현재 곡 버전·현재 특별 목적 표시 조회 |
| PATCH `/special-event-reviews/{id}` | 확정·무시·명시적인 재검토 처리 |
| GET `/special-event-reviews/{id}/audit` | 수동 결정 이력 |

응답의 `basisCurrent`는 저장된 근거가 현재 대표 영상·참여 멤버·날짜와 일치하고 만료되지 않았는지를 나타낸다. 오래된 근거로 확정하려 하면 `SPECIAL_REVIEW_BASIS_CHANGED` 409다. PENDING 후보를 다시 생성하면 근거·활성 여부가 달라질 때 후보 버전을 올린다. 조회수 갱신처럼 원본 관측 시각만 갱신된 경우는 근거 버전을 올리지 않는다.

확정 예시:

```json
{
  "version": 0,
  "songVersion": 4,
  "status": "CONFIRMED",
  "label": "생일 기념 커버",
  "reason": "공식 공개 안내 확인"
}
```

CONFIRMED는 `isSpecialEvent=true`와 관리자 자유 입력 `label`(1~60자)을 함께 저장한다. 곡·후보 버전을 각각 검사하고 변경과 두 감사 이력을 같은 트랜잭션에서 기록한다. DISMISSED는 후보만 무시하며 곡의 기존 표시를 변경하지 않는다. 확정·무시한 후보는 자동 수집·재판정으로 되살리거나 덮어쓰지 않는다. 수정하려면 PATCH로 `status=PENDING`을 명시해 현재 날짜 근거로 다시 검토한다. 재검토로 돌려도 기존 곡 표시를 자동 해제하지 않는다. 표시 해제·문구 수정·날짜 후보 없는 수동 표시는 기존 `/songs/{id}` 관리 API를 사용한다.

자동 검토는 웹 프로세스에서 기본 60초마다 최대 50곡씩 처리한다. DB 커서를 저장해 전체 곡을 차례로 확인하며 다중 인스턴스는 PostgreSQL 트랜잭션 잠금으로 중복 실행을 막는다. 갱신 작업은 10초 제한이며 실패한 배치의 커서와 후보 변경은 함께 롤백된다. 관리자 변경은 곡 → 영상 → 멤버 순서로 잠가 판정 중 근거가 바뀌지 않게 한다.

원본 날짜 근거는 관측 후 30일에 만료된다. 만료된 근거는 즉시 응답에서 숨기고 별도 정리 트랜잭션으로 제거한다. 자동 후보 생성이 꺼져도 정리는 진행한다. 관리자 확정·무시 상태와 수동 라벨은 유지한다. 감사 이력에는 자동 판정의 영상 날짜·원본 제목을 복사하지 않는다.

## 적용 설정

1. 웹 마이그레이션 계정으로 V16·V17을 적용한다. 기존 곡·후보·특별 목적 표시를 수정하거나 일괄 분류하지 않는다. `DB_RUNTIME_ROLE`을 사용하므로 런타임 역할 이름이 달라도 적용 가능하며 수집 역할이 없어도 마이그레이션할 수 있다.
2. 제목 분류를 켜기 전에 스키마 소유자로 [분류 규칙 읽기 권한](../infra/sql/collection-rule-grants.sql)을 수집 역할에 추가 적용한다. 기존 V8/V9 권한 스크립트는 이전 마이그레이션에서도 그대로 사용 가능하다. 새 권한은 규칙 SELECT만 허용한다.
3. 기존 `DISCOVERY_CLASSIFICATION_ALLOWED`는 정책 확인 전 false를 유지한다. 규칙 편집·미리보기 자체가 이 설정을 활성화하지 않는다.
4. 날짜 후보 생성·확정·재검토는 `SPECIAL_EVENT_POLICY_ALLOWED=true`가 필요하다. false면 생성·확정·재검토 요청은 `SPECIAL_REVIEW_POLICY_DISABLED` 503이다. 조회·무시는 가능하다.
5. 주기적 후보 생성은 웹에 `SPECIAL_EVENT_REVIEW_ENABLED=true`도 설정한다. 두 설정 모두 기본 false다. 새 Google 키나 OAuth 권한은 필요하지 않다.

검증은 모의 YouTube와 격리된 PostgreSQL에서 수행한다. 실제 운영 정책 확인·운영 DB 권한 적용·관리자 화면 연동은 별도 단계다.
