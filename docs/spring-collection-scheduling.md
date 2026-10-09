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

## 전환 순서와 되돌리기

Render Free의 절전 중에는 내부 스케줄러도 멈춘다. 연속 실행 방식을 검증한 후 전환한다.

1. 테스트를 통과한 코드를 main에 병합·배포한다. 이 시점에는 Spring 스케줄러를 비활성으로 두고 GitHub 예약을 유지한다.
2. Render에 수집 전용 변수와 활성화 플래그를 추가하여 재배포한다.
3. 실제 웹·수집·탐색의 성공 기록과 웹 기능이 정상인지 확인한다. 전환 중 중첩된 기존 수집은 DB 잠금으로 보호한다.
4. GitHub 저장소 Variable `COLLECTOR_SCHEDULE_OWNER=render`를 설정한다. 이후 예약 job은 건너뛰며 승인된 수동 실행은 계속 사용할 수 있다. 이 값이 없으면 기존 GitHub 예약 동작을 유지한다.

되돌릴 때에는 Render의 `COLLECTOR_SCHEDULED_ENABLED=false`를 설정하고 GitHub의 `COLLECTOR_SCHEDULE_OWNER` 값을 제거한다. 기존 GitHub 수집 Secrets는 복구·수동 실행을 위해 유지한다. DB 백업 workflow는 이 전환의 영향을 받지 않는다.

## 공식 문서

- [Spring 예약 실행](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [Render Free 제한](https://render.com/docs/free)
