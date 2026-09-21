# Stelody 백엔드 개발 지침

## 기술과 구조

- Java 21, Spring Boot, Spring MVC, Spring Security, Spring Session JDBC, Gradle Wrapper를 사용한다. Spring 의존성은 Boot BOM을 우선한다.
- PostgreSQL, Spring Data JPA, 필요한 조회에 JdbcClient, 스키마 변경에 Flyway를 사용한다.
- auth, user, song, member, playlist, collection, admin 등 기능별 패키지로 나누고 내부에 controller, service, repository, domain, dto를 둔다.
- Controller는 HTTP 변환과 입력 검증, Service는 업무 규칙과 트랜잭션, Repository는 DB 접근을 맡는다.
- DTO는 Java record를 기본으로 한다. JPA Entity를 API 응답으로 직접 반환하지 않고 엔티티에 Lombok @Data를 사용하지 않는다.
- 모든 서비스에 인터페이스를 만들거나 범용 BaseService를 도입하지 않는다. 실제로 필요한 경계에만 추상화를 둔다.

## 인증과 권한

- Spring Security와 JDBC 세션을 사용한다. 로그인 시 세션 ID를 갱신하고 로그아웃 시 서버 세션을 무효화한다.
- CSRF를 전역 비활성화하지 않는다. 로그아웃은 POST로 처리한다.
- 개인 데이터의 소유권과 관리자 권한은 서버에서 검증한다. 클라이언트가 전달한 ownerId나 역할을 신뢰하지 않는다.

## DB와 수집

- DB 컬럼은 snake_case를 사용한다. FK·unique·check 제약으로 데이터 일관성을 보장한다.
- 트랜잭션은 서비스 계층에서 관리한다. open-in-view=false, 연관관계는 LAZY를 기본으로 하고 목록 조회의 N+1을 확인한다.
- 적용 완료된 Flyway 마이그레이션은 수정하지 않고 새 마이그레이션을 추가한다. 운영에서는 ddl-auto=validate를 사용한다.
- 목록 추가·삭제·재정렬은 부모 version을 갱신한다. 재정렬은 전체 항목을 검증하고 하나의 트랜잭션으로 저장한다.
- 수집 작업은 재시도해도 중복 저장되지 않도록 한다. 수집 결과가 관리자 수정·반려 결정을 덮어쓰지 않게 한다.

## 실행과 테스트

- backend/에서 Gradle Wrapper와 등록된 task로 실행·포맷·테스트·빌드를 수행한다.
- 로컬 DB와 메일은 Docker Compose의 PostgreSQL과 Mailpit을 사용한다. 외부 API는 WireMock과 fixture로 대체해 독립적으로 개발한다.
- JUnit, MockMvc, PostgreSQL Testcontainers로 검증한다. PostgreSQL 통합 테스트를 H2로 대체하지 않는다.
- 관련 변경 시 인증·소유권·CSRF·세션 무효화·동시 수정·마이그레이션·중복 수집·관리자 수정 보존을 검증한다.
- Spotless와 google-java-format을 사용한다.

### 명령 등록

현재 Gradle 프로젝트와 Compose 설정은 미구성이다. 초기 구성 후 아래 항목을 실제 검증한 명령으로 교체한다. Gradle 명령의 실행 위치는 backend/이다.

| 작업 | 등록할 명령 |
|---|---|
| 로컬 DB·메일 시작 | 실제 Compose 파일 경로, 실행 디렉터리와 서비스명 |
| 앱 실행 | 개발 프로필을 적용한 실행 task |
| 단일 테스트·전체 테스트 | 클래스 지정 방식과 전체 실행 task |
| 포맷 적용·검사 | Spotless tasks |
| 빌드 | 패키징 및 검증 task |

- Java 21과 Docker 실행 상태를 확인한다. Testcontainers 테스트는 사용 가능한 Docker 환경이 필요하다.

### 테스트 배치

- 테스트는 src/test/java 아래 운영 코드의 기능별 패키지에 맞춰 둔다. fixture와 테스트 설정은 src/test/resources에 둔다.
- 기존 공통 컨테이너·인증·외부 API 테스트 설정을 재사용한다. 첫 구성 후 공통 설정 경로와 대표 테스트 경로를 이 항목에 등록한다.
- 계산·판정 규칙은 단위 테스트로, DB 제약·트랜잭션·권한 경계는 통합 테스트로 검증한다. DB 통합 테스트에 실제 Flyway 마이그레이션을 적용한다.
- 테스트 간 DB 상태와 mock 응답이 누출되지 않게 초기화한다. 실제 메일 발송이나 운영 API 키를 요구하지 않는다.
