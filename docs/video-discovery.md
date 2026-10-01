# 공식 채널 영상 탐색

## 범위

사용자가 제공한 공식 그룹·개인 채널 11곳의 업로드 목록에서 검토 후보를 수집한다. `collection_enabled=true`인 `GROUP`·`MEMBER` 채널만 탐색한다. 곡·작품·참여자·대표 영상은 생성하거나 변경하지 않는다. 채널 소유자를 참여자로 추정하지 않으며 그룹 채널의 영상을 모든 멤버의 곡으로 연결하지 않는다.

관리자 후보 조회·무시·복원 API는 후속 작업이다. 곡 등록·연결·공개, 참여자 확정, 규칙 편집, 관리자 계정 지정, 수동 재시도 버튼은 후속 작업이다. 공개 곡·멤버 API는 계속 로그인 없이 이용할 수 있다.

## 준비와 실행

1. 서버의 마이그레이션 계정으로 V9을 적용한다. 기존 V1~V8은 유지한다. 수집 역할이 없어도 마이그레이션할 수 있다.
2. 스키마 소유자로 기존 [수집 권한](../infra/sql/collector-grants.sql)과 새 [탐색 권한](../infra/sql/discovery-grants.sql)을 적용한다. 역할 이름이 다르면 먼저 스크립트를 수정한다.
3. 스키마 소유자로 [공식 채널 등록](../infra/sql/official-channels.sql)을 수동 적용한다. 이 스크립트는 사용자가 준 11개 핸들을 실제 API로 확인한 채널 ID를 사용한다. 기존 채널은 덮어쓰지 않는다. 개인 채널의 `member_id`는 실제 멤버 자료 확인 전까지 비워 둔다.
4. 기존 `COLLECTOR_*`, `YOUTUBE_API_KEY` 설정에 `DISCOVERY_ENABLED=true`를 추가한다. 제목 자동 분류는 정책 확인 전까지 `DISCOVERY_CLASSIFICATION_ALLOWED=false`로 유지한다. 운영에서 활성화하기 전에 해당 스키마·권한·허용 목록을 먼저 준비한다.

```sh
# backend 디렉토리; 환경변수는 실행 환경에 별도로 주입
./gradlew bootJar --no-daemon
java -jar build/libs/stelody-0.0.1-SNAPSHOT.jar --collector --discover
```

`--collector`만 전달하면 기존 등록 영상 갱신만 실행한다. `--discover`는 등록 영상 갱신 후 신규 탐색을 같은 프로세스에서 실행한다. 둘의 합계 실행 예산은 10분이며 동일한 DB 잠금을 사용한다. 탐색이 비활성인데 `--discover`를 전달하면 해당 실행 전체를 건너뛴다. 자격 증명·DB 접속 없이 종료 코드 0이다.

IntelliJ의 기존 `StelodyCollector` 설정에 **프로그램 인수** `--collector --discover`와 환경변수 `DISCOVERY_ENABLED=true`를 사용한다. `.env.collector.local`을 사용하는 경우에도 IntelliJ가 해당 파일을 읽도록 연결해야 한다. Spring Boot는 `.env`를 자동으로 읽지 않는다. Google 로그인 비밀값은 수집 기능에 필요하지 않다.

## 페이지와 재개

- `channels.list`의 uploads ID를 확인하고 `playlistItems.list`를 최대 50개 단위로 조회한다. 플레이리스트 항목 ID와 영상 ID를 구분한다. 페이지 토큰은 불투명한 값으로 보관·인코딩한다.
- 채널 최초 탐색은 최신 한 페이지만 처리한다. 이후 과거 페이지 토큰을 별도로 보관한다. 전체 업로드를 한꺼번에 수집하지 않는다.
- 신규 탐색은 마지막 완료 때의 첫 영상 ID를 경계로 사용한다. 경계를 만나거나 목록 끝에 도달하면 완료 지점을 교체한다. 한 실행에서 기본 채널당 3페이지, 최대 5페이지이며 남은 페이지는 다음 실행에서 재개한다.
- 후보 반영과 체크포인트 변경은 같은 트랜잭션이다. 부분 실패·할당량 초과·시간 제한이 발생해도 완료된 페이지는 유지한다. 미완료 탐색은 2시간 대기 없이 재개하며 완료 채널은 마지막 탐색 후 2시간이 지나야 다시 목록을 조회한다.
- `SUCCEEDED`는 이번 실행이 정상 종료됐다는 뜻이다. 페이지 상한에 도달한 채널은 `in_progress=true`와 다음 토큰을 유지할 수 있다. 전체 과거 목록 완료 여부는 `backfill_complete`로 확인한다.
- 잘못된 페이지 토큰은 해당 커서를 초기화하고 실행을 실패 처리한다. 다음 실행에서 처음부터 다시 찾되 기존 영상 ID 경계·후보의 유일성·무시 기록을 유지한다.
- 등록 영상과 무시 후보는 상세 API를 다시 요청하지 않는다. 비공개·예약·누락 후보는 `DEFERRED`로 두고 2시간 후 채널당 최대 50개를 재확인한다. API 호출 전체 실패를 영상 삭제로 판정하지 않는다.
- 페이지 저장 전에 채널 허용 여부와 실행 소유 토큰을 다시 확인한다. 외부 채널 응답·잘못된 응답은 페이지 전체를 반영하지 않는다.

공식 계약: [채널 조회](https://developers.google.com/youtube/v3/docs/channels/list), [업로드 목록 조회](https://developers.google.com/youtube/v3/docs/playlistItems/list), [플레이리스트 항목](https://developers.google.com/youtube/v3/docs/playlistItems).

### 과거 목록 수동 탐색

최초 탐색이 완료된 채널의 내부 UUID를 지정한다. 이 실행은 등록 영상 갱신을 생략하고 과거 목록만 처리한다. 신규 탐색 지점과 과거 커서는 분리한다.

```sh
java -jar build/libs/stelody-0.0.1-SNAPSHOT.jar --collector --discover --backfill --channel=<채널 UUID> --max-pages=1
```

`--max-pages`는 1~5, 과거 탐색 기본값은 1이다. 채널 없이 과거 탐색을 요청하면 설정 오류로 중단한다. 초기 탐색이 없는 채널은 `DISCOVERY_REQUIRED_FIRST`로 중단한다. GitHub 수동 실행의 `discover` 옵션은 신규 탐색용이며 과거 탐색은 위 명령으로 실행한다.

## 분류와 수동 판단

규칙 버전은 `title-v1`이다. 자동 분류가 꺼져 있으면 공개 후보도 `REVIEW / UNKNOWN / CLASSIFICATION_DISABLED`로 저장한다. 이 경우 음악 이외의 업로드도 검토 후보에 포함될 수 있다.

분류를 명시적으로 허용하면 제목의 단어 경계를 검사한다. Cover·Covered by·歌ってみた는 커버 제안, Original·MV·오리지널은 오리지널 제안이다. Discovery·Recover 같은 부분 문자열을 Cover로 처리하지 않는다. 명시적인 Shorts·Clip·Livestream·[클립]·[방송]·[다시보기] 표기는 제외 제안을 우선한다. 길이만으로 Shorts를 판정하지 않는다. 원곡 제목에 제외 단어가 포함된 경우도 제안이 틀릴 수 있으므로 관리자 판단이 필요하다.

| 필드 | 값과 의미 |
|---|---|
| disposition | REVIEW: 검토, EXCLUDED: 제외 제안, DEFERRED: 공개 상태·원본 재확인 필요 |
| suggestedType | UNKNOWN, COVER, ORIGINAL; 확정 곡 유형이 아님 |
| reviewStatus | PENDING: 검토 대상, IGNORED: 관리자가 무시 |
| decisionReason | CLASSIFICATION_DISABLED, EXPLICIT_EXCLUSION_MARKER, COVER_REQUIRES_PARTICIPANT_REVIEW, ORIGINAL_REQUIRES_REVIEW, TYPE_UNCONFIRMED, NOT_PUBLIC, SOURCE_EXPIRED |

규칙 제안과 수동 판단은 분리한다. `IGNORED` 후보는 재탐색으로 복원되지 않는다. `PENDING` 복원은 검토 대상으로 되돌리며 곡 등록·공개 또는 분류 변경을 의미하지 않는다. 어떤 규칙도 이번 단계에서 자동 공개하지 않는다.

## 권한과 보관

웹 실행 역할은 후보를 읽는다. 수집 역할은 원본·규칙 제안·버전만 갱신하며 수동 메모·판단 시각을 읽거나 수동 판단을 쓰지 못한다. 회원·개인 목록·세션·곡 생성 권한도 없다.

YouTube 원본은 마지막 관측 후 30일이 지나면 다음 탐색 실행에서 비운다. 중복·무시 식별을 위한 영상 ID와 관리자 판단·메모는 유지한다. 탐색 실행 이력은 종료 후 30일에 정리한다. 탐색을 장기간 중지할 때에는 별도 보관 정리도 운영에서 처리해야 한다.

## 정기 실행과 검증

기존 workflow는 매시간 UTC `:17`에 등록 영상을 갱신한다. 짝수 UTC 시간에는 저장소 Variable `DISCOVERY_ENABLED=true`일 때 탐색도 실행한다. 수동 실행의 `discover=true`도 같은 Variable을 요구한다. `DISCOVERY_CLASSIFICATION_ALLOWED`는 별도 Variable이며 기본 비활성이다. 실제 GitHub 활성화·운영 DB 적용은 이번 로컬 검증과 별개다.

```sh
./gradlew test --tests '*DiscoveryRulesTest' --tests '*DiscoveryOptionsTest' --tests '*YouTubeUploadsClientTest' --no-daemon
./gradlew integrationTest --tests '*DiscoveryCollectorIntegrationTest' --tests '*DiscoveryMigrationIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

검증 대상은 페이지 재개·과거 커서 분리·무시/등록 중복 방지·토큰 인코딩·잘못된 토큰·할당량·시간 제한·채널 비활성·다른 채널 응답·잠금·역할 분리·보관 정리·기존 V8 업그레이드다.
