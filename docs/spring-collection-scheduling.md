# Spring 서버 정기 수집

Render 웹 서버에서 수집을 실행한다. `--collector` 일회성 실행도 같은 실행 코드를 사용한다. 기본 설정은 비활성이며 이 문서 작성만으로 운영 전환이 완료되지 않는다.

## 실행 방식

- `COLLECTOR_ENABLED=true`와 `COLLECTOR_SCHEDULED_ENABLED=true`를 함께 설정해야 활성화된다. collector CLI 프로필에서는 웹 스케줄러를 생성하지 않는다.
- 기동 1분 후부터 작업 종료 후 1분 간격으로 DB 완료 기록을 확인한다. 수집용 단일 스레드를 따로 사용하며 웹의 기본 예약 실행 스레드를 점유하지 않는다.
- 조회수 갱신은 매시간 `:17`부터 해당 UTC 시간 슬롯의 성공 여부를 확인하여 실행한다. 최근 48시간의 중단된 작업이 있으면 기존 수집기 규칙대로 먼저 재개하고 다음 확인에서 현재 슬롯을 처리한다.
- 신규 탐색은 UTC 짝수 시각 `:17`, 즉 한국 시간 홀수 시각 `:17`을 기준으로 각 채널의 실제 탐색 완료 시각을 확인한다. 해당 기준보다 오래되고 2시간이 지난 채널이나 아직 탐색하지 않은 채널이 있으면 실행한다. 페이지 처리가 미완료인 채널은 다음 확인에서 이어간다. 재시작 후 놓친 탐색도 같은 방식으로 처리한다. `DISCOVERY_ENABLED=false`이면 탐색 테이블을 조회하지 않는다.
- 재시도 기능을 활성화하면 접수된 관리자 요청도 다음 확인에서 하나씩 처리한다. 요청만 처리한 경우 일반 수집은 다음 확인에서 이어간다.
- 실패 후에는 15분 동안 자동 재시도를 멈춘다. 긴 수집·DB 지연·재시작으로 실제 시작 시각은 늦어질 수 있다. 멈춰 있던 시간의 과거 조회수는 만들어 넣지 않는다.
- 기존 PostgreSQL advisory lock·소유 토큰·최대 10분 예산·정책 플래그를 유지한다. 수집 전용 연결 풀은 최대 2개이며 Spring의 웹/JPA/세션 DataSource로 등록하지 않는다.

수집 오류는 Render 로그의 `collector`, `discovery`, `scheduled collector` 오류 코드와 기존 관리자 수집 상태 API에서 확인한다. GitHub에서 수행하던 수집 실패 이메일은 Render 내부 수집에는 적용되지 않는다. HTTP 상태 확인 알림은 서버 장애를 확인하며 수집 성공을 증명하지 않는다.

## Render 설정

기존 `prod,google,render` 프로필을 유지한다. **collector 프로필을 웹에 추가하지 않는다.** 다음 변수만 기존 설정에 추가하며, 기존 웹 DB 계정을 수집에 재사용하지 않는다.

| 변수 | 값 |
|---|---|
| `COLLECTOR_ENABLED` | `true` |
| `COLLECTOR_SCHEDULED_ENABLED` | `true` |
| `COLLECTOR_DB_URL` | 기존 수집 전용 JDBC URL. CA 경로를 `/tmp/stelody-db-ca.crt`로 변경 |
| `COLLECTOR_DB_USERNAME` / `COLLECTOR_DB_PASSWORD` | 기존 수집 전용 계정 |
| `YOUTUBE_API_KEY` | 기존 수집 API 키 |
| `DISCOVERY_ENABLED` | 기존 탐색 설정 |
| `COLLECTION_RETRY_ENABLED` / `DISCOVERY_CLASSIFICATION_ALLOWED` / `COVER_AUTO_PUBLICATION_POLICY_ALLOWED` | 기존 승인된 설정 유지 |

Render 시작 스크립트가 기존 `DB_CA_CERTIFICATE_BASE64`로 만드는 공개 CA 파일을 재사용한다. 인증서 검증은 `sslmode=verify-full`을 유지한다. GitHub runner의 `/tmp/stelody-collector-db-ca.crt` 경로를 그대로 사용하면 Render에서 연결할 수 없다. 비공개 실제 값은 Git·PR·채팅에 넣지 않는다.

## 자체 요청으로 절전 방지 시험

먼저 외부 상태 확인 서비스 없이 `SELF_KEEP_ALIVE_ENABLED=true`를 사용한다. 이 기능은 render 웹 프로필에서만 생성하며, 기동 1분 후부터 요청 종료 후 5분 간격으로 Render가 제공하는 `RENDER_EXTERNAL_URL`의 `/actuator/health`를 호출한다. 수집용 스레드와 분리된 전용 스레드에서 실행하므로 10분 수집 중에도 요청을 보낸다.

- 공개 HTTPS `*.onrender.com` 루트 주소만 허용한다. 로컬호스트·다른 호스트·사용자 정보·쿼리·조각·별도 경로는 거절한다.
- 연결은 최대 5초, 요청은 최대 10초이며 리다이렉트를 따라가지 않는다. GET 요청에는 DB·YouTube·Google 자격 증명·세션 쿠키를 전달하지 않는다.
- 성공 시 `selfKeepAlive status=SUCCEEDED httpStatus=200`, 실패 시 정규화된 오류 코드만 기록한다. 응답 본문·원문 예외·설정 주소는 로그에 남기지 않는다. 실패해도 다음 예약 요청은 계속한다.
- JVM 내부 작업이나 localhost 접속만으로 절전을 막는다고 가정하지 않는다. 공개 주소로 실제 HTTP 요청이 왕복해야 한다.

Render Free는 요청이 15분 없으면 잠들며 자체 재시작도 가능하다. 자체 요청이 절전 카운터를 갱신한다는 공식 보장은 없으므로 **운영에서 실제 검증하기 전에는 절전 방지 성공으로 기록하지 않는다.** 서버가 이미 잠들었거나 멈춘 경우 이 코드도 실행되지 않으며, 외부 요청이 있어야 다시 시작할 수 있다.

### 배포 후 확인

1. 배포가 Live가 된 후 실제 자체 요청의 첫 HTTP 200 로그를 확인한다.
2. 브라우저 자동 새로고침·외부 모니터 등 다른 요청을 보내지 않고 30분 이상 둔다.
3. 이후 최초 외부 확인 요청 전에 이미 생성된 자체 요청 로그와 다음 수집의 DB 기록을 확인한다. 확인 요청 자체가 서버를 깨울 수 있으므로 단순히 health UP인 것만으로 성공을 판단하지 않는다.
4. 자체 요청 사이에 긴 공백·절전·수집 누락이 발생하면 원인을 확인하고 외부 5분 상태 확인으로 전환한다.

### 효과가 없을 때의 대안

`SELF_KEEP_ALIVE_ENABLED=false`로 끄고 [UptimeRobot](https://uptimerobot.com/)의 Free HTTP(s) 모니터를 추가한다. URL은 `https://stelody-backend.onrender.com/actuator/health`, 간격은 5분, 알림은 본인의 이메일로 설정한다. 공개 상태 확인 주소만 전달하며 DB·Google·YouTube 비밀 값은 필요 없다. 이 외부 모니터도 연속 실행을 보장하지 않는다.

서버가 하나여도 다른 무료 서비스가 추가되면 Render 워크스페이스 전체 750시간 한도를 함께 사용한다.

## 전환 순서와 되돌리기

1. 테스트를 통과한 코드를 main에 병합·배포한다. 이 시점에는 Spring 스케줄러를 비활성으로 두고 GitHub 예약을 유지한다.
2. `SELF_KEEP_ALIVE_ENABLED=true`를 추가한다. 외부 모니터는 먼저 등록하지 않는다.
3. Render에 수집 전용 변수와 활성화 플래그를 추가하여 재배포한다. `RENDER_EXTERNAL_URL`은 Render 제공 값을 사용한다.
4. 자체 요청의 HTTP 200과 실제 웹·수집·탐색의 성공 기록, 웹 기능이 정상인지 확인한다. 전환 중 중첩된 기존 수집은 DB 잠금으로 보호한다.
5. GitHub 저장소 Variable `COLLECTOR_SCHEDULE_OWNER=render`를 설정한다. 이후 예약 job은 건너뛰며 승인된 수동 실행은 계속 사용할 수 있다. 이 값이 없으면 기존 GitHub 예약 동작을 유지한다.
6. 위의 외부 요청 없는 관찰 절차로 자체 요청의 절전 방지 효과와 다음 시간의 수집 기록을 확인한다. 효과가 없으면 외부 상태 확인으로 전환한다.

되돌릴 때에는 Render의 `COLLECTOR_SCHEDULED_ENABLED=false`를 설정하고 GitHub의 `COLLECTOR_SCHEDULE_OWNER` 값을 제거한다. 기존 GitHub 수집 Secrets는 복구·수동 실행을 위해 유지한다. DB 백업 workflow는 이 전환의 영향을 받지 않는다.

## 공식 문서

- [Spring 예약 실행](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [Render Free 제한](https://render.com/docs/free)
- [UptimeRobot 모니터 설정](https://help.uptimerobot.com/en/articles/11358364-how-to-create-your-first-monitor-on-uptimerobot-quick-setup-guide)
