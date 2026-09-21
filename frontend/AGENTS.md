# Stelody 프론트엔드 개발 지침

## 기술과 구조

- Node.js와 npm 버전은 실제 런타임 설정 및 package.json에 맞춘다. 초기 구성 시 호환되는 버전을 고정한다.
- React, Vite, TypeScript strict, npm을 사용한다. 의존성은 package-lock.json으로 고정하고 CI에서는 npm ci를 사용한다.
- React Router, TanStack Query, React Hook Form, Zod, Tailwind CSS, shadcn/ui를 사용한다.
- src는 app, pages, features, shared로 구성한다. features는 auth, songs, members, favorites, playlists, admin으로 나눈다.
- React 로컬 상태는 useState/useReducer, 서버 데이터는 TanStack Query, 검색·필터·정렬은 URL, 인증 상태는 /me 응답으로 관리한다.

## API와 상태 처리

- TanStack Query가 관리하는 서버 데이터를 별도 useEffect와 로컬 상태로 중복 관리하지 않는다.
- API 호출은 공통 fetch 모듈에서 오류·CSRF·응답 처리를 일원화한다.
- CSRF 토큰은 /api/v1/auth/csrf에서 받아 변경 요청에 전달하고 로그인·로그아웃 후 다시 받는다.
- 로그아웃 시 개인 Query 캐시를 제거한다. 개인 서버 데이터를 localStorage에 복제하지 않는다.
- 로딩·빈 결과·오류·접근 불가·저장 중 상태를 처리한다. 중복 요청을 방지하고 저장 실패 시 이전 상태를 복원한다.
- 목록 편집 중 409 응답을 받으면 충돌을 안내하고 최신 상태를 다시 조회한다. 사용자 편집을 자동 재전송해 덮어쓰지 않는다.

## UI 구현

- 버튼·입력·Dialog는 기존 공통 UI 컴포넌트를 우선 사용한다. 테마 색상은 공통 CSS 변수와 의미 기반 토큰을 사용한다.

- 검색은 300ms debounce, 이전 요청 취소, 한글 IME 조합 중 검색 억제를 적용한다.
- 무한 스크롤은 로딩 중 중복 요청과 마지막 페이지 이후 요청을 막는다. 결과는 ID로 중복 제거한다.
- 키보드 조작, Dialog 포커스, 접근성 이름, 모바일·데스크톱 및 라이트·다크 테마를 지원한다.

## 실행과 테스트

- frontend/에서 package.json에 등록된 scripts로 개발·타입 검사·테스트·빌드를 수행한다.
- Vitest와 React Testing Library로 입력과 상태 전이를, Playwright로 핵심 사용자 흐름을 검증한다.
- MSW와 fixture로 API 성공·실패·지연을 재현한다. 실제 외부 API 없이도 개발과 테스트가 가능하게 한다.
- 관련 변경 시 저장 실패 복원, 인증 만료, 한글 입력, 무한 스크롤 종료, 키보드·포커스 동작을 검증한다.
- ESLint와 Prettier를 사용한다.

### 명령 등록

현재 앱 scripts는 미구성이다. 초기 구성 후 아래 항목을 실제 검증한 명령으로 교체한다. 실행 위치는 frontend/이며 단일 테스트는 watch가 아닌 종료되는 실행 방식을 등록한다.

| 작업 | 등록할 명령 |
|---|---|
| 설치 | lockfile 생성 후 npm ci 검증 |
| 개발 서버 | package.json의 개발 script |
| 타입·lint·포맷 검사 | 해당 scripts |
| 단일 테스트·전체 테스트 | 파일 지정 방식과 전체 실행 script |
| E2E | Playwright script와 앱 서버 실행 조건 |
| 프로덕션 빌드 | 빌드 script |

### 테스트 배치

- 기존 테스트 배치와 공통 렌더링·MSW 설정을 재사용한다. 초기에는 단위·컴포넌트 테스트를 대상 코드 옆의 *.test.ts 또는 *.test.tsx에, Playwright 테스트는 e2e/에 둔다.
- 첫 테스트 구성 후 공통 설정 경로와 대표 테스트 경로를 이 항목에 등록한다. 아직 존재하지 않는 테스트 파일을 예시 구현으로 취급하지 않는다.
- 성공뿐 아니라 관련 오류·지연·사용자 입력 상태를 검증한다. 비동기 UI는 고정 시간 sleep 대신 테스트 도구의 상태 대기를 사용한다.
