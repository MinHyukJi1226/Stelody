# YouTube 플레이리스트 내보내기

## 사용자 흐름과 경계

2026-10-03 사용자 합의: 새 **비공개** YouTube 재생목록으로 복사하고, 이용 불가 곡은 제외하며 결과에 제외 개수를 표시한다. 사이트의 공개 곡 탐색은 로그인 없이 유지한다. 개인 목록과 내보내기는 로그인한 소유자만 사용한다.

Google 로그인은 기존 `openid email` 권한을 유지한다. 내보내기에 필요한 추가 동의는 별도로 받으며, 거절·만료·연결 해제가 Stelody 로그인과 개인 목록을 삭제하지 않는다. 연결 대상 Google `sub`가 현재 회원의 Google 계정과 같아야 한다. 다른 계정으로 연결하거나 기존 YouTube 목록에 덮어쓰는 기능은 제공하지 않는다.

새 목록의 이름과 곡 순서는 요청 당시 사이트 목록에서 복사한다. 이후 사이트 목록 이름·순서·대표 영상이 바뀌어도 작업 스냅샷은 변하지 않는다. 작업 중 사이트 공개 상태가 바뀌거나 기존 대표가 교체되면 해당 미복사 항목은 제외한다. 복사 직전 YouTube videos.list로 PUBLIC·processed 상태를 재확인하고 누락·비공개 영상도 제외한다. 사이트 목록과 YouTube 목록의 자동 동기화는 제공하지 않는다.

## 추가 OAuth 연결

모든 엔드포인트는 현재 세션을 사용한다. 변경 요청에는 실제 CSRF 토큰이 필요하다. 응답은 `Cache-Control: no-store`이며 비밀키·토큰·인증 코드·이메일을 반환하지 않는다.

| 요청 | 응답과 동작 |
| --- | --- |
| GET `/api/v1/me/youtube/connection` | `{status}`: DISABLED / DISCONNECTED / CONNECTED / RECONNECT_REQUIRED / REVOKING |
| POST `/api/v1/me/youtube/authorizations` | `{authorizationUrl,expiresAt}`. 브라우저를 Google 동의 화면으로 이동한다. 자동으로 내보내지 않는다. |
| GET `/api/v1/me/youtube/callback?state=...&code=...` | Google 전용 반환 경로. 추가 동의를 검증한 뒤 코드 없는 연결 상태 경로로 303 이동한다. |
| DELETE `/api/v1/me/youtube/connection` | 미완료 작업 중단 및 Google 권한 철회. 철회 요청이 실패하면 REVOKING 상태를 유지하며 작업자가 다시 요청한다. |

`youtube.force-ssl`과 계정 확인용 `openid email`을 요청한다. OAuth state는 회원·로그인 세션에 바인딩하고 5분 내 한 번만 소비한다. PKCE S256, nonce와 ID 토큰 서명·issuer·audience·만료·sub를 검증한다. 새로운 동의나 연결 해제 후 오래된 콜백은 연결을 다시 활성화할 수 없다. 부분 동의로 YouTube scope가 빠지면 연결하지 않는다.

access/refresh 토큰과 임시 PKCE verifier는 AES-256-GCM으로 암호화한다. 사용자 ID와 데이터 용도를 인증된 추가 데이터로 묶어 다른 계정이나 필드로 옮긴 암호문을 사용할 수 없게 한다. state와 세션 식별자는 해시만 저장한다. Google 토큰 교환·갱신에는 Spring Security OAuth 클라이언트를 사용한다. access 토큰 만료 전 갱신하고 refresh 토큰 교체도 암호화해 저장한다. `invalid_grant`나 철회된 권한은 재연결 상태로 전환한다.

연결 해제는 사이트 인증 세션을 유지한다. Google에서 철회하는 권한은 같은 OAuth 클라이언트의 결합된 grant에 영향을 줄 수 있으므로 다음 Google 로그인·연결에서 재동의가 필요할 수 있다. 이미 생성된 YouTube 목록은 사용자 계정에 남는다.

## 내보내기 작업

POST `/api/v1/me/playlists/{id}/youtube-exports`

```json
{"requestId":"클라이언트가 생성한 UUID","version":3}
```

202 Accepted와 `Location: /api/v1/me/youtube-exports/{작업ID}`를 반환한다. 동일 회원의 같은 requestId·목록ID·version은 같은 작업을 반환한다. requestId를 다른 내용에 재사용하면 409다. 새 요청에는 현재 목록 version이 필요하며 타인 목록은 404, 버전 충돌은 409다.

사용자당 미완료 작업 하나, 요청당 최대 500곡으로 제한한다. 이용 불가 곡은 SKIPPED로 기록한다. 내보낼 수 있는 곡이 하나도 없으면 409 `NO_EXPORTABLE_SONGS`로 거절하고 빈 YouTube 목록을 만들지 않는다. 처음 제외된 항목은 같은 작업의 재시도에서 다시 포함하지 않는다.

| 요청 | 용도 |
| --- | --- |
| GET `/api/v1/me/youtube-exports/{id}` | 소유자의 진행·결과 확인 |
| POST `/api/v1/me/youtube-exports/{id}/retry` | FAILED / UNCERTAIN 작업 재개 요청. 실행 중·완료 작업에는 멱등 응답 |
| POST `/api/v1/me/youtube-exports/{id}/cancel` | 후속 요청 중단. 이미 생성된 외부 목록·곡을 삭제하지 않음 |

응답은 `id/playlistId/version/status/totalCount/copiedCount/skippedCount/remainingCount/youtubePlaylistId/youtubeUrl/errorCode/createdAt/updatedAt`다. 소유자 외 요청은 404다. totalCount는 원래 목록 개수이고 copied+skipped+remaining이 total과 같다. 외부 목록 ID는 생성 확인 후에만 표시한다. 오류 코드에 Google의 원문 메시지나 요청 파라미터를 포함하지 않는다.

| status | 의미 |
| --- | --- |
| QUEUED | 백그라운드 처리 대기 |
| RUNNING | 복사 진행 중. 서버 중단 후 저장된 상태부터 재개 |
| SUCCEEDED | 모든 항목을 복사 또는 제외 처리함 |
| FAILED | API가 거절하거나 읽기·토큰 처리 실패. 원인 해결 후 같은 작업 재시도 가능 |
| UNCERTAIN | 쓰기 요청의 결과가 확인되지 않음. 조회로 성공이 확인될 때만 다음 쓰기를 진행 |
| CANCELLED | 취소·연결 해제로 후속 작업 중단 |

## 중간 실패와 중복 방지

작업자는 HTTP 서버 안에서 실행하며 DB에 대기 작업과 항목별 결과를 저장한다. Redis·별도 메시지 브로커는 사용하지 않는다. PostgreSQL 세션 advisory lock으로 여러 프로세스의 작업자가 겹치지 않게 한다. 외부 쓰기 전에 in-flight 기록을 먼저 커밋하고 응답을 받은 뒤 결과를 커밋한다. API 대기 시간 동안 목록 전체를 잠그거나 DB 트랜잭션을 열어두지 않는다. 토큰 갱신은 계정 잠금 아래 제한된 시간으로 직렬화한다.

- 재생목록 생성 결과가 불확실하면 사용자 계정의 목록에서 작업 UUID를 넣은 설명을 찾는다. 유일한 비공개 목록을 확인한 경우에만 기존 ID로 이어간다.
- 곡 추가 결과가 불확실하면 외부 곡 순서가 기존 복사 항목과 해당 요청 곡에 정확히 일치하는지 확인한다. 일치하면 완료로 기록한다.
- 결과가 없거나 아직 반영되지 않았으면 UNCERTAIN을 유지한다. 같은 쓰기를 맹목적으로 다시 요청하지 않는다. 목록 탐색은 최대 1,000개로 제한하며 범위 내에서 확인할 수 없으면 수동 확인이 필요하다.
- 재시도 전에 외부 목록의 현재 순서가 저장된 완료 항목과 일치하는지 검사한다. 사용자가 외부 목록을 편집한 경우 `REMOTE_PLAYLIST_CHANGED`로 중단한다.
- 429·할당량 초과 등 확정 거절은 FAILED 상태로 멈춘다. 할당량 회복 후 같은 작업을 재시도한다. 재생목록을 다시 만들거나 완료된 곡을 다시 추가하지 않는다.
- 한 번의 작업자 실행에서 최대 10곡을 처리한다. 취소·연결 해제는 다음 쓰기를 막는다. 이미 전송되어 처리 중인 외부 요청은 취소할 수 없어 취소 결과에도 일부 생성된 목록·곡이 남을 수 있다.
- 외부 클라이언트는 HTTP/1.1을 사용한다. 연결/읽기 제한은 3초/5초이며 redirect를 따라 토큰을 전달하지 않는다. 인증 토큰은 Authorization Bearer 헤더로 전달한다. 영상 상태·외부 순서 조회가 실패하면 삭제나 비공개로 단정하지 않고 작업을 멈춘다.
- 항목의 첫 페이지 조회가 HTTP 404 `playlistNotFound`를 반환하면 1초·2초 간격으로 추가 조회해 총 3번까지 확인한다. 다른 오류나 두 번째 이후 페이지에는 이 재시도를 적용하지 않는다. 404가 계속되면 FAILED로 멈추며 빈 목록으로 간주하거나 쓰기를 반복하지 않는다. 이후 같은 작업을 재시도하면 저장된 외부 목록 ID로 이어간다.

무료 서버가 멈춰 있는 동안에는 작업도 진행되지 않는다. 다시 기동하면 DB 기록으로 이어간다. 불확실한 외부 쓰기의 무조건 반복을 피하며 외부 API에 원자적 exactly-once 기능이 있다고 가정하지 않는다.

## 설정과 실제 계정 검증

기본 `YOUTUBE_EXPORT_ENABLED=false`. 실제 사용 전 다음을 준비한다.

1. 기존 Google Cloud 웹 OAuth 클라이언트에 개발용 승인된 리디렉션 URI `http://localhost:8080/api/v1/me/youtube/callback`을 **추가**한다. 기존 로그인 콜백도 유지한다.
2. Google 인증 플랫폼의 데이터 액세스에서 `https://www.googleapis.com/auth/youtube.force-ssl` scope를 추가하고 개발 계정을 테스트 사용자로 설정한다. 기존 YouTube Data API v3 사용 설정을 유지한다. 실제 운영 공개에는 해당 OAuth 권한의 검증·동의 화면·개인정보 안내 등 외부 조건을 별도로 확인한다.
3. IntelliJ의 **StelodyApplication** 실행 설정에서 환경 변수에 `YOUTUBE_EXPORT_ENABLED=true`, `YOUTUBE_EXPORT_REDIRECT_URI=http://localhost:8080/api/v1/me/youtube/callback`과 `YOUTUBE_TOKEN_ENCRYPTION_KEY`를 추가한다. 기존 GOOGLE_CLIENT_ID/SECRET과 `SPRING_PROFILES_ACTIVE=local,google`은 유지한다. 수집 전용 StelodyCollector 설정에 추가하는 것이 아니다. YOUTUBE_API_KEY는 이 사용자 작업의 인증 수단으로 사용하지 않는다.
4. 암호화 키는 로컬에서 `openssl rand -base64 32`로 생성하고 비공개 환경 설정에만 저장한다. 키를 채팅·커밋·공유 실행 설정에 넣지 않는다. 키를 유지하지 못하면 기존 암호문을 사용할 수 없으므로 Google 권한을 직접 철회하고 재연결해야 한다.
5. V14를 마이그레이션 계정으로 적용한다. 새 테이블은 웹 역할만 접근하며 수집·운영 역할에는 토큰·개인 목록 접근 권한을 추가하지 않는다. Supabase 연결은 **session pooler** 또는 직접 연결을 사용해야 한다. transaction pooler로 세션 작업 잠금을 유지한다고 가정하지 않는다.
6. 개발 계정에서 작은 목록을 명시적으로 내보내 실제 비공개 목록·순서·제외 결과를 확인한다. 구현 단계에서는 실제 사용자 Google grant나 YouTube 목록을 변경하지 않았다.

[재생목록 생성](https://developers.google.com/youtube/v3/docs/playlists/insert)과 [곡 추가](https://developers.google.com/youtube/v3/docs/playlistItems/insert)는 각각 요청당 50 quota units다. 많은 곡은 프로젝트의 일일 할당량을 소진할 수 있으며 500곡이 한 번에 완료된다고 보장하지 않는다. 수집과 내보내기는 같은 Google 프로젝트의 할당량을 공유한다. 할당량 실패·재시도는 진행 상태로 표시한다.

## 보관과 운영

작업 스냅샷·외부 목록 ID는 생성 후 30일 이내에 정리하며, 이후 requestId 멱등 보장도 만료된다. 인증 요청은 5분, 30일 이상 사용하지 않은 YouTube 연결과 정지된 계정의 연결은 작업자가 철회한다. 철회 완료 또는 이미 무효인 토큰 확인 후 암호문을 삭제한다. 철회 중에는 재연결·내보내기를 허용하지 않는다. 기능을 비활성화해도 철회 처리를 이어가려면 암호화 키를 유지해야 한다.

회원 탈퇴 기능에는 연결 철회와 복구 시 토큰 무효화 처리를 함께 붙여야 한다. 새 테이블의 회원 FK는 삭제 시 개인 작업·연결 자료를 제거한다. 백업·복원 운영에서는 임시 OAuth 요청과 토큰을 복구해 다시 활성화하지 않는다. Google scope 외부 검증과 실제 계정 시험은 자동 테스트로 대신 완료 처리하지 않는다.

## 검증

```sh
# backend, Java 21과 Docker 실행
./gradlew test --tests '*TokenCipherTest' --no-daemon
./gradlew integrationTest --tests '*YouTubeExportIntegrationTest' --tests '*ExportMigrationIntegrationTest' --no-daemon
./gradlew check bootJar --no-daemon
```

WireMock의 서명된 모의 Google ID 토큰·토큰 API와 YouTube API, 역할 분리 PostgreSQL·JDBC 세션·CSRF로 검증한다. 실제 계정·실제 Google grant·YouTube 목록은 테스트 fixture로 사용하지 않는다.

2026-10-03, Java 21과 Docker 환경에서 `GRADLE_USER_HOME=/tmp/stelody-gradle ./gradlew spotlessApply check bootJar --no-daemon`을 실행해 성공했다. 단위 테스트 90개와 통합 테스트 238개, 총 328개가 통과했으며 실패·오류·건너뛴 테스트는 없다.

추가 동의의 회원·세션 바인딩과 일회성 소비, 잘못된 ID 토큰 거절, 토큰 암호화·갱신·철회, 역할별 DB 권한, 목록 소유권·버전·중복 요청, 비공개 생성·순서·이용 불가 제외, 500곡 상한과 실행당 10곡 처리, 할당량 실패 후 재개, 불확실한 쓰기의 조회 확인, 서버 재시작·동시 작업자·취소·보관 기간 정리를 검증했다.

2026-10-03에는 사용자가 개발용 Google Cloud 리디렉션·scope·테스트 사용자 설정을 추가하고 실제 개발 계정으로 동의했다. 콜백 후 연결 상태 API의 `CONNECTED` 응답을 확인했다. 공개 곡이 없어 사용자 승인으로 수집 후보 중 실제 커버 영상 2개를 로컬에 임시 등록했다. 시험에서 새 비공개 목록 생성은 확인했으나, 생성 후 항목 조회에서 `playlistNotFound`가 발생해 0곡 복사·FAILED로 종료했다. 이후 기존 토큰으로 같은 목록·항목을 읽었을 때 두 요청 모두 HTTP 200, 비공개·0곡을 확인했다. 이는 생성 직후 조회의 반영 지연 가능성을 보여주지만 Google 내부 원인은 직접 확인하지 못했다.

첫 페이지 404의 제한된 조회 재시도와 기존 목록 재개를 추가했다. 모의 API에서 404 두 번 후 200으로 성공하는 경우, 계속되는 404에 쓰기 없이 중단한 뒤 같은 목록으로 재개하는 경우, 할당량 오류에는 조회 재시도를 하지 않는 경우를 검증했다.

보완 후 사용자가 기존 작업을 재시도해 실제 계정 내보내기도 성공했다. 시험 화면과 DB 모두 `SUCCEEDED`, 전체 2곡·복사 2곡·제외 0곡·남음 0곡을 확인했다. YouTube 화면에서 기존 비공개 목록 `PLYmJFc8gOJkM`에 불가행력(아오쿠모 린, `Qi_JLmXBFFo`), 아이와 나의 바다(유즈하 리코, `blFr5w6o2jQ`)가 이 순서로 들어간 것을 확인했다. DB의 작업 UUID `1c2d3e2a-65d4-4796-a325-99c654f15f70`와 외부 목록 ID도 유지됐으며, 같은 원본 목록의 작업은 1개였다. 이번 재시험은 기존 작업·목록 재개와 실제 곡 복사를 확인한 것이며, 생성 직후 404의 자동 조회 재시도는 모의 API로 검증했다. 실제 이용 불가 곡 제외·토큰 갱신·철회와 운영용 Google 검증은 별도 확인이 남아 있다. 임시 등록 자료와 시험 UI는 로컬 검증 자료이며 프로젝트 코드에 포함하지 않는다.

보완 후 `GRADLE_USER_HOME=/tmp/stelody-gradle ./gradlew check bootJar --no-daemon`도 성공했다. 단위 90개·통합 241개, 총 331개가 실패·오류·건너뛴 테스트 없이 통과했다. IntelliJ의 기존 StelodyApplication 실행 설정으로 재실행하고 로컬 health 응답 HTTP 200 / UP을 확인했다.

PR #11의 P2 리뷰를 반영해 재인증 시작 시 연결의 `updated_at`도 갱신한다. 31일 지난 연결에서 재인증을 시작하고 정리 작업을 실행하는 통합 테스트를 추가했다. 수정 전에는 예상 `CONNECTED`와 달리 `DISCONNECTED`가 되어 실패했다. 수정 후에는 연결 상태·인증 generation이 유지되고 철회 요청 없이 콜백이 성공해 새 암호화 토큰이 저장된다. 기존의 미사용 연결 정리와 정지 계정 철회 테스트도 통과했다. `GRADLE_USER_HOME=/tmp/stelody-gradle ./gradlew spotlessApply check bootJar --no-daemon` 성공, 단위 90개·통합 242개로 총 332개가 실패·오류·건너뛴 테스트 없이 통과했다. 이 재현에는 모의 OAuth API를 사용했으며 실제 Google 계정에서 31일 미사용 상황을 재현하지 않았다.

공식 근거: [Google 서버 OAuth·점진적 권한 요청·갱신·철회](https://developers.google.com/identity/protocols/oauth2/web-server), [재생목록 조회](https://developers.google.com/youtube/v3/docs/playlists/list), [곡 목록 조회](https://developers.google.com/youtube/v3/docs/playlistItems/list).
