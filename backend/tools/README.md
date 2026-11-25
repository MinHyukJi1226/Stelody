# 마이그레이션 통합과 운영 이력 전환

현재 `db/migration`은 `V1__initial_schema.sql` 하나로 시작합니다. 구 V1~V35를 모두 적용한
빈 DB의 최종 테이블·함수·제약·인덱스·권한·초기값을 포함하며 다음 변경은 V2입니다.
API·실제 운영 데이터·공개 주소·사용자별 version 값은 변경하지 않습니다.

기존 DB에는 초기 SQL을 실행하지 않습니다. 일반 앱 기동에서도 이력 전환을 자동 수행하지
않습니다. 기존 이력에서 새 앱의 Flyway 검증을 실행하면 실패하므로 아래 순서를 지킵니다.
`baselineOnMigrate`, `repair` 또는 검증 무시 설정으로 우회하지 않습니다.

## 검사와 사전 검증

- PostgreSQL 17의 `psql`, Python 3.11 이상, 앱과 같은 버전의 Flyway CLI 또는 전용
  마이그레이터, 스키마 소유자 전용 자격 증명을 사용합니다.
  연결은 기존 `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSFILE/PGSSLMODE/PGSSLROOTCERT`로
  설정합니다. 운영 TLS 검증과 비공개 파일 권한을 유지하고 비밀번호를 인자로 전달하지 않습니다.
- 구 V35까지 모두 적용돼 있어야 합니다. `legacy-v35-history.json`은 원본 35개 SQL의
  Flyway 체크섬·파일명·버전을 고정합니다. 체크섬 변경·실패·누락·추가 이력은 거절합니다.
- 아래 명령은 읽기 전용이며 이력·카탈로그·세션을 바꾸지 않습니다.

```sh
python3 backend/tools/rebaseline.py \
  --database=<대상_DB명> --runtime-role=stelody_app
```

`READY_TO_REBASELINE`은 이력 사전 검사 통과를 뜻합니다. 앱 기능·백업 복원 검증이나
쓰기 작업 중지 확인을 대신하지 않습니다. `REBASELINE_FAILED`는 완료가 아닙니다.

## 운영 전환 순서

1. 통합 릴리스의 CI·격리 DB 검증을 완료하고 기존 이미지·구버전 소스·DB 백업을 확보합니다.
2. 새 릴리스 자동 배포를 보류합니다. 이전 이미지와 그 설정을 기록합니다.
3. 모든 웹 인스턴스·수집기·예약 작업·기타 마이그레이터와 백업 작업을 중지합니다.
   새 요청이나 별도 관리 도구의 쓰기가 유입되지 않도록 유지합니다.
4. 중지 시점의 암호화 DB 백업을 생성하고 업로드·다시 읽기 검증을 확인합니다.
   복원 검증이 된 백업 도구를 사용합니다. 이력 파일은 DB 백업을 대체하지 않습니다.
5. 운영 변경 승인 후 아래 명령으로 이력만 전환합니다. 새 이력 파일은 600 권한으로 생성되며
   이미 존재하는 경로·심볼릭 링크는 덮어쓰지 않습니다. DB명·소유자·기존 이력을 검사하고,
   런타임/수집 연결을 거절하며, app/session 테이블 쓰기를 잠근 한 트랜잭션으로 처리합니다.

```sh
python3 backend/tools/rebaseline.py \
  --database=<대상_DB명> --runtime-role=stelody_app \
  --apply --services-stopped \
  --backup-reference=<검증한_암호화_DB_백업_식별자> \
  --history-backup=/비공개경로/legacy-v35-history.json
```

6. `REBASELINED` 뒤 새 코드의 Flyway validate/migrate를 실행합니다. V1 초기 SQL이 다시
   실행되지 않는지 확인하고 새 이미지를 배포합니다. 일반 앱의 운영 Flyway가 비활성이라면
   별도 마이그레이터 검증을 생략하지 않습니다.
7. health, 로그인/CSRF/로그인 유지, 공개 검색·그룹·곡 상세·통계, 개인 목록 저장,
   관리자 등록, YouTube 연결/내보내기·탈퇴를 시험 계정으로 확인합니다.
8. 통합 V1을 지원하는 백업 도구로 새 백업과 격리 복원을 확인한 뒤 백업·수집·외부 트래픽·
   자동 배포를 재개합니다. 보관 정책에 따라 이전 백업과 복구 키를 유지합니다.

프로세스의 중지 플래그와 백업 식별자는 운영자의 확인입니다. 도구가 호스팅 서비스를
중지하거나 외부 백업을 다운로드해서 대신 검증하지는 않습니다. 실패하면 서비스를 중지한
상태에서 원인을 확인합니다. 안전한 실패 메시지 외 DB 오류 원문은 출력하지 않습니다.

Flyway CLI를 사용한다면 `FLYWAY_URL/FLYWAY_USER/FLYWAY_PASSWORD`를 동일한 DB·소유자·
TLS 검증 설정으로 비공개 환경에 주입하고 다음과 같이 검사합니다. libpq의 `PG*` 설정이
Flyway에 자동 적용되는 것은 아닙니다. 비밀번호를 명령 인자나 저장소에 넣지 않습니다.

```sh
flyway -locations=filesystem:backend/src/main/resources/db/migration \
  -defaultSchema=app -schemas=app,session -placeholders.runtimeRole=stelody_app validate
flyway -locations=filesystem:backend/src/main/resources/db/migration \
  -defaultSchema=app -schemas=app,session -placeholders.runtimeRole=stelody_app migrate
```

통합 릴리스에서는 현재 버전 1·추가 실행 0건이어야 합니다. 활성 디렉터리에 V2 이상이 있는
다른 릴리스로 이 명령을 실행하지 않습니다.

## 이력 원복

새 V2 이후 변경이 아직 없고 이력이 전환 직후의 기준점 한 건일 때만 원복할 수 있습니다.
모든 작업을 다시 중지한 뒤 먼저 `--apply` 없이 검사합니다.

```sh
python3 backend/tools/rebaseline.py \
  --database=<대상_DB명> --runtime-role=stelody_app \
  --restore-history=/비공개경로/legacy-v35-history.json

python3 backend/tools/rebaseline.py \
  --database=<대상_DB명> --runtime-role=stelody_app \
  --restore-history=/비공개경로/legacy-v35-history.json \
  --apply --services-stopped --backup-reference=<검증한_DB_백업_식별자>
```

이력 파일의 DB명·소유자·원본 35개 체크섬을 다시 검사하며 기존 이력 행을 그대로 복구합니다.
`LEGACY_HISTORY_RESTORED` 뒤 이전 이미지/설정으로 돌아가 검증하고 서비스를 재개합니다.
후속 V2가 적용됐으면 이 도구는 원복을 거절합니다. 해당 변경에 대한 별도 복구 절차가 필요합니다.

## 백업 호환과 과거 스크립트

- 이전 백업은 기존 `format: 1`, `schemaVersion: 19~35` 형식을 그대로 읽습니다.
- 통합 이후 백업은 `schemaVersion: 1`, `schemaGeneration: "squashed-v1"`을 함께 기록합니다.
  구 V1의 세션 테이블만 있는 DB를 새 V1로 오인하지 않습니다. 미래 버전은 검증 없이
  자동 허용하지 않으며, V2 추가 시 백업 검증도 함께 확장해야 합니다.
- 새 V1은 구 V35와 같은 제외 정책을 적용합니다. 세션·자동 로그인 자격·OAuth 토큰·
  탈퇴 조회 권한·수집 캐시는 복원하지 않고, 목록·아티스트 등록 중복 방지 이력은 보존합니다.
  탈퇴 기록 재적용과 저장 자료의 기존 만료 정책도 유지합니다.
- 구 백업 복원은 해당 백업의 구조·기존 이력을 보존합니다. 구 V19~V34 백업은 호환되는
  구 릴리스/마이그레이터로 V35까지 올리고 검증한 뒤 위 전환 절차를 거칩니다.
  새 V1 코드를 구 백업에 바로 실행하지 않습니다. 구 V35 백업은 직접 전환할 수 있습니다.
- 원본 SQL은 Git 이력에 보존됩니다. 회귀 테스트 전용 `src/test/resources/db/legacy-v35.zip`
  한 개에도 원본을 고정해 과거 업그레이드·백업 복원 검증을 유지합니다. 운영 JAR에는
  포함하지 않으며 운영 Flyway 검색 경로에는 통합 SQL 한 개만 둡니다.

구 백업을 복원한 격리 DB에 V35까지 적용할 때는 고정 원본을 별도 디렉터리에 풀어 사용해도
됩니다. 새 V1과 같은 Flyway 검색 경로에 섞지 않습니다. 마이그레이션 중 원본이 정의한
충돌 검사가 실패하면 기존 데이터를 덮어쓰지 말고 원인을 먼저 확인합니다.

```sh
python3 -m zipfile -e backend/src/test/resources/db/legacy-v35.zip /비공개경로/legacy-v35-sql
flyway -locations=filesystem:/비공개경로/legacy-v35-sql \
  -defaultSchema=app -schemas=app,session -placeholders.runtimeRole=stelody_app -target=35 migrate
```

구조·권한·초기값 비교, 데이터/세션/토큰 보존, 검사 모드, 이력 원복, V2 1회 적용,
활성 연결·다른 DB·변조/누락/실패 이력 거절은 `SchemaSquashIntegrationTest`로 확인합니다.
구 백업 및 신규/전환 V1 백업의 실제 pg_dump/pg_restore는 `BackupSchemaIntegrationTest`로 확인합니다.
