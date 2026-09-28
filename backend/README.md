# Stelody 백엔드

스텔라이브 노래 정보를 모아 검색하고 개인 목록에 저장하는 서비스입니다.

## 백엔드 개발 환경

Java 21, Gradle Wrapper 8.14.3, Spring Boot 4.1.1을 사용합니다. Docker Compose가 필요합니다.

아래 명령은 `backend/` 디렉토리에서 실행합니다.

```sh
docker compose -f ../infra/compose.yml up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

- 서버: http://localhost:8080
- 상태 확인: http://localhost:8080/actuator/health
- 로컬 메일함: http://localhost:8025 (메일 발송 연동은 인증 기능에서 추가)
- 현재 health 외 경로는 차단되어 있습니다. 업무 API는 아직 구현하지 않았습니다.

로컬 PostgreSQL의 기본 계정은 `stelody`, 비밀번호는 `local-only-password`입니다. 로컬 포트는 루프백에만 바인딩합니다. 운영에서는 [환경변수 예시](../.env.example)의 환경변수를 별도 주입하세요. Spring Boot는 루트 `.env`를 자동으로 읽지 않습니다. 운영 DB URL은 TLS 인증서 검증을 사용하고 실행 계정과 마이그레이션 계정을 분리해야 합니다.

## 검증

```sh
./gradlew spotlessApply        # Java 포맷 적용
./gradlew test                 # Docker 없이 보안 경계 테스트
./gradlew check bootJar        # 포맷·단위·PostgreSQL 통합 테스트·실행 jar
```

`integrationTest`와 `check`는 실행 중인 Docker가 필요합니다. Docker가 없으면 실패하며 통합 검증을 조용히 건너뛰지 않습니다. H2를 사용하지 않습니다. Flyway가 app/session 스키마와 세션 테이블을 생성하며 JPA는 스키마를 변경하지 않습니다.

현재 작업은 백엔드 기반 구성입니다. 인증과 곡·검색·개인 목록·수집 기능은 기능별로 구현하고 검증 결과를 공유합니다. 최근 요구사항 합의는 [백엔드 구현 기준 보완](../docs/backend-decisions.md)에 기록했습니다.
