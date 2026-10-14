# Stelody 프론트엔드

React SPA의 실행·API 연동·검증 기반과 라이트·다크 공통 UI입니다. 기능별 화면과 API 연동은 후속 작업에서 구현합니다.

## 로컬 실행

Node.js 24 LTS와 npm 10 이상을 사용합니다. `frontend/`에서 실행합니다.

```sh
nvm use
npm ci
npm run dev
```

화면은 `http://localhost:5173`입니다. `/api` 요청은 기본적으로 `http://localhost:8080`으로 전달합니다. 백엔드는 [백엔드 실행 안내](../backend/README.md)를 따릅니다. 프록시 목적지를 바꾸려면 `.env.example`을 `.env.local`로 복사하고 `API_PROXY_TARGET`을 설정합니다.

`VITE_API_BASE_URL`은 브라우저 요청 주소이며 기본값은 `/api/v1`입니다. 배포 구조가 정해지면 이 값과 호스팅 프록시·CORS·쿠키·OAuth 콜백을 함께 검증합니다. Vite 환경변수는 사용자에게 공개되므로 비밀키를 넣지 않습니다. Google 로그인은 실제 브라우저 이동으로 연결하며 세션 쿠키를 JavaScript로 읽지 않습니다.

## 구조

- `src/app`: 앱 시작, 라우터, Query 설정, 전역 스타일
- `src/pages`: 경로별 화면
- `src/features`: 후속 기능별 모듈을 둘 위치. 아직 기능은 구현하지 않았습니다.
- `src/shared`: API, 공통 유틸리티와 후속 UI 컴포넌트
- `src/test`: Vitest·React Testing Library·MSW 기반
- `e2e`: Playwright 브라우저 검증

Tailwind Vite 플러그인과 shadcn/ui의 `components.json`·경로 별칭을 준비했습니다. 공통 `Button`은 Radix Slot과 cva를 사용하며 링크에도 같은 스타일을 적용합니다. 폼·차트·드래그 패키지는 해당 기능을 시작할 때 추가합니다.

## 공통 UI 진행 상태

- 디자인 출처: [데스크톱 다크 헤더 38:6069](https://www.figma.com/design/XNF3BzVHQNt38qhNl0psyB?node-id=38-6069), [수정된 모바일 헤더 8:30](https://www.figma.com/design/XNF3BzVHQNt38qhNl0psyB?node-id=8-30), [UI KIT 7:2](https://www.figma.com/design/XNF3BzVHQNt38qhNl0psyB?node-id=7-2), [보관함 선택 버튼 17:3186](https://www.figma.com/design/XNF3BzVHQNt38qhNl0psyB?node-id=17-3186). MCP 한도 이후에는 사용자 승인에 따라 Figma 데스크톱 앱의 변수·레이어 속성·캔버스로 확인했습니다.
- `Stelody · Color`와 `Stelody · Dark` 변수, Noto Sans KR 가변 폰트, 공통 버튼 변형, 입력의 기본·오류·비활성 상태, 키보드로 이동 가능한 탭을 적용했습니다.
- 로고·해 아이콘은 Figma MCP 제공 에셋이며, 달 아이콘(75:9684)과 햄버거 버튼(165:10769)은 앱에서 SVG로 복사해 그대로 저장했습니다. 햄버거는 원본 SVG를 마스크로 사용해 테마별 ink 색상을 적용합니다. 에셋은 `src/shared/assets`에 보관합니다.
- 모바일 헤더는 브랜드·테마·햄버거 버튼의 한 줄 구조입니다. 확인된 규격은 높이 93px, 여백 20px, 요소 간격 8px입니다. 햄버거 버튼은 테두리 없는 44×44px 터치 영역입니다. 메뉴의 닫힘·외부 클릭·Escape·포커스 복귀는 Radix Popover로 처리합니다. 모바일 메뉴 모듈은 해당 화면에서만 로드합니다.
- 기본 테마는 시스템 설정을 따릅니다. 직접 선택한 값만 `stelody-theme`에 저장하며 저장소가 차단돼도 화면 전환은 가능합니다. 계정 데이터는 저장하지 않습니다.
- 공통 헤더와 메뉴 이동, 본문 건너뛰기, 단일 main 영역을 구성했습니다. 검색·멤버·보관함·로그인 화면은 준비 중 상태입니다. 실제 계정 상태나 로그인 요청은 아직 연결하지 않았습니다.

## API 계약

`openapi/stelody.json`은 2026-10-13 운영 OpenAPI에서 확인한 스냅샷입니다. 백엔드 파라미터 스키마 수정 기준 커밋은 `f05fe1dbb15c0c04089f9fbbd871e01b4a7dec4b`입니다. 운영 명세에서 검색 필터와 검색어·커서·OAuth 문자열 제약의 수정 반영을 확인했습니다. CI 아티팩트와 운영 명세의 바이트 일치를 별도로 검증하지는 않았습니다.

명세는 수동으로 편집하지 않습니다. 백엔드 변경 시 해당 커밋의 검증된 CI 아티팩트에서 교체하고 아래 명령으로 타입을 생성합니다.

```sh
npm run api:generate
npm run api:check
```

생성 타입은 `src/shared/api/generated/schema.d.ts`에 보관합니다. `api:check`는 외부 서버를 호출하지 않고 저장된 명세에서 다시 생성한 결과를 비교합니다. 새 타입이 현재 운영 서버와 일치하는지는 연동 시 별도 확인합니다.

공통 클라이언트는 `/`로 시작하는 API 상대 경로를 받습니다. 공개 GET은 CSRF를 조회하지 않고, 변경 요청은 메모리에 보관한 CSRF 토큰을 사용합니다. 동시 변경의 토큰 조회는 공유합니다. `401`·`403` 및 로그아웃 후 토큰을 비웁니다. 로그인·재인증 완료 후 기능 모듈에서 `apiClient.invalidateCsrf()`를 호출해야 합니다. 다음 변경 요청에서 새 토큰을 조회합니다.

변경 요청은 자동 재시도하지 않습니다. `ApiError`의 `status`, `code`, `fieldErrors`, `traceId`로 분기하고, 알 수 없는 오류는 일반 안내로 처리합니다. 검색 요청 취소에는 `signal`을 전달합니다. 요청의 제네릭 타입은 TypeScript 계약이며 응답 전체의 런타임 검증을 의미하지 않습니다.

인증 UI와 로그아웃의 개인 Query 캐시 제거는 후속 인증 작업 범위입니다. 개인 데이터를 localStorage에 저장하지 않습니다.

## 검증

```sh
npm run check
npx playwright install chromium
npm run test:e2e
```

`check`는 API 생성 타입, lint, 포맷, 단위·모듈 테스트, 타입 및 프로덕션 빌드를 검사합니다. E2E는 빌드된 앱을 4173 포트로 실행해 데스크톱·모바일의 직접 접근·새로고침·404·키보드 복귀를 확인합니다.

CI는 기존 Backend CI에서 재사용 Frontend CI를 호출합니다. 최종 `required-checks`와 main 이미지 게시가 프론트 검증 성공도 요구합니다. 배포 방식과 프론트 배포 산출물 연결은 아직 변경하지 않았습니다.

홈 본문은 실행 확인용이며 Figma 메인 화면의 구현 결과가 아닙니다. 실제 Google·YouTube·관리자 계정 연동은 후속 기능에서 검증합니다.
