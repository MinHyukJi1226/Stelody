# 운영용 관리자 지정

V11(운영 역할·감사) 마이그레이션은 운영 계정 존재 없이 적용할 수 있다. 웹 실행 계정에는 app_user의 role/status 변경 권한을 주지 않는다. 운영자는 별도 `stelody_operator` 로그인과 CONNECT를 준비한 뒤 스키마 소유자로 `infra/sql/admin-operator-grants.sql`을 적용한다. 이 계정은 사용자 id/role/status 조회·role 변경·감사 삽입만 허용하며 이메일·개인 목록·수집 원본을 읽지 못한다.

backend 디렉토리에서 비공개 환경에 `ADMIN_DB_URL`, `ADMIN_DB_USERNAME`, `ADMIN_DB_PASSWORD`를 설정한다. 웹/수집 자격 증명으로 자동 대체하지 않는다.

```sh
java -jar build/libs/stelody-0.0.1-SNAPSHOT.jar --admin-account \
  --user-id=00000000-0000-0000-0000-000000000001 \
  --role=ADMIN --reason='운영 담당자 확인'
```

대상은 `/api/v1/me`에서 확인한 기존 계정 UUID다. 이메일로 찾거나 계정을 새로 만들지 않는다. ACTIVE 계정만 변경하며 `--role=USER`로 철회한다. 같은 역할은 성공한 no-op이며 중복 이력을 만들지 않는다. DB 변경과 이력은 원자적이고 실패는 종료 코드 1이다. 명령은 웹 서버·OAuth·JPA·Flyway·수집을 기동하지 않는다. 전용 프로필은 operator이며 웹 서버 실행에 사용하지 않는다. 비밀번호·이메일·사유·예외 내용을 로그에 기록하지 않는다.

관리자 승격·철회는 기존 로그인 세션에도 다음 요청부터 현재 DB 역할 검증을 통해 반영된다. 실제 사용자 계정을 승격하거나 운영 DB를 변경하지 않았다.

## 독립 검증

2026-10-02. 운영 명령·V11·관련 테스트만 추출한 임시 사본에서 `./gradlew check bootJar --no-daemon` 성공: 단위 65개·통합 158개, 총 223개 통과(실패·오류·건너뜀 0개). 지정·철회·동일 역할 no-op, 정지/없는 계정/잘못된 인수 거절, 감사 실패 시 역할 변경 롤백, 운영 계정의 이메일/상태 접근 금지, 웹 계정의 역할 UPDATE·역할을 포함한 INSERT 금지를 검증했다. 실제 jar에서 운영 자격 증명을 제거한 `--admin-account` 실행도 서버·JPA·Flyway 기동 없이 종료 코드 1로 안전하게 종료됐다.
