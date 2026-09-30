# 개인 플레이리스트 API

모든 경로는 `/api/v1`로 시작하며 로그인 세션이 필요하다. 소유자는 세션에서 결정한다. 다른 회원의 목록과 없는 목록은 모두 404 `PLAYLIST_NOT_FOUND`다. 공개 곡·멤버 조회는 로그인 없이 가능하다.

변경 요청은 `/api/v1/auth/csrf`의 헤더·토큰과 세션 쿠키를 함께 보낸다. 개인 응답은 `Cache-Control: no-store`다.

| 메서드·경로 | 입력 | 성공 응답 |
|---|---|---|
| POST `/me/playlists` | `{name}` | 201 Summary와 상세 Location |
| GET `/me/playlists` | `size`, `cursor` | Page |
| GET `/me/playlists/{id}` | — | Summary |
| PATCH `/me/playlists/{id}` | `{name, version}` | 200 Summary |
| DELETE `/me/playlists/{id}` | 쿼리 `version` | 204 |

이름은 앞뒤 Unicode 공백을 제거하고 1~50 코드 포인트를 허용한다. 내부 공백과 표시 원문을 유지하며 이름 중복도 허용한다. 계정당 기본 50개 목록이며 `PLAYLIST_LIMIT`로 조정한다. 회원 행 잠금으로 동시 생성 한도를 지킨다.

생성 버전은 0이며 이름 변경 성공 시 한 번 증가한다. 같은 이름의 유효한 변경도 증가한다. 수정·삭제에는 현재 버전이 필요하며 JPA `@Version`으로 검증한다. 오래된 버전·동시 충돌은 409 `PLAYLIST_CHANGED`, 잘못된 입력은 400 `INVALID_PLAYLIST_REQUEST`, 생성 한도는 409 `PLAYLIST_LIMIT_REACHED`다. 실패한 변경은 롤백한다. 비로그인·만료 계정은 401, CSRF 불일치·누락은 403이다.

Summary는 `{id, name, version, createdAt, updatedAt, totalCount, availableCount}`, Page는 `{items: Summary[], nextCursor, hasNext, totalCount}`다. Summary의 수량은 저장 곡·이용 가능 곡 수, Page의 수량은 내 목록 개수다. 페이지 크기는 기본 20·최대 50이며 `createdAt DESC, id DESC`로 조회한다. 커서는 소유자·생성 시각·목록 ID에 묶인다. 조회·집계는 같은 DB 스냅샷을 사용한다.

V7는 목록과 항목 테이블, FK·중복 곡·위치 제약·조회 인덱스를 추가한다. 목록 수량 집계와 목록·회원 삭제 시 항목 연쇄 삭제를 위한 스키마를 함께 준비한다. 실행 역할은 두 테이블의 SELECT·INSERT·UPDATE·DELETE만 갖는다. V6 기존 회원·곡·즐겨찾기·세션 보존, 권한·제약, 세션 소유권·실제 CSRF·50개 경계 동시 생성·버전 충돌을 PostgreSQL Testcontainers로 검증한다.

```sh
# backend 디렉토리에서 Docker 실행 후
./gradlew check bootJar --no-daemon
```

곡 추가·조회·삭제, 전체 순서 변경, 프론트 화면과 YouTube 내보내기는 후속 단계다.
