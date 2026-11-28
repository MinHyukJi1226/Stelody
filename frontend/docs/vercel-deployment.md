# Vercel 프론트 배포

React SPA는 Vercel, API는 기존 `https://stelody-backend.onrender.com`을 사용합니다. `stelody.xyz`는 아직 구매·연결하지 않은 예정 도메인입니다. 먼저 Vercel 프로젝트의 고정 Production 도메인(`https://<프로젝트>.vercel.app`)으로 확인합니다. 프로젝트 이름과 실제 URL은 프로젝트 생성 후 확인하며 임의의 주소를 OAuth 설정에 등록하지 않습니다.

## 1. 프로젝트 생성 설정

GitHub의 `MinHyukJi1226/Stelody` 저장소를 가져와 다음 값을 지정합니다. 이 작업은 배포 설정이 포함된 커밋을 GitHub에 올린 뒤 진행합니다.

| 설정                | 값                                         |
| ------------------- | ------------------------------------------ |
| Framework Preset    | Vite                                       |
| Root Directory      | `frontend`                                 |
| Production Branch   | `main`                                     |
| Node.js Version     | `24.x`                                     |
| Install Command     | `npm ci`                                   |
| Build Command       | `npm run build`                            |
| Output Directory    | `dist`                                     |
| `VITE_API_BASE_URL` | `/api/v1` (미설정 시에도 코드 기본값 동일) |

명령과 출력 경로는 `frontend/vercel.json`에도 명시되어 있습니다. `API_PROXY_TARGET`은 로컬 Vite 개발 프록시 전용이므로 Vercel에 설정하지 않습니다. Render의 DB 접속 정보, Google client secret, 토큰 암호화 키는 프론트 환경변수에 넣지 않습니다.

`npm run preview`를 운영 서버 명령으로 등록하지 않습니다. Vercel이 `dist`의 정적 파일을 제공합니다. Vercel 프로젝트를 생성하거나 외부 설정을 변경하는 작업은 이 저장소 설정만으로 실행되지 않습니다.

## 2. 요청 처리

- `/api/:path*`를 기존 Render의 `/api/:path*`로 먼저 전달합니다. `/api/v1` 접두사와 쿼리를 유지하며, 공개 API와 인증·CSRF·로그아웃·보관함 API에 같은 규칙을 적용합니다.
- `/api`의 외부 rewrite 캐싱을 `x-vercel-enable-rewrite-caching: 0`으로 비활성화하고 `Cache-Control: no-store`를 지정합니다. 사용자별 API 응답을 CDN에 저장하지 않는 구성입니다.
- 나머지 화면 경로는 `/index.html`로 rewrite합니다. `/members`, `/songs/...`, `/library`, `/login`을 주소창으로 열거나 새로고침해도 React Router가 처리합니다. API 오류를 SPA 화면으로 바꾸는 규칙은 추가하지 않습니다.
- 정적 파일은 Vercel의 정적 파일 제공을 사용합니다. 프론트에서 쿠키를 복사하거나 토큰을 읽는 프록시 코드는 추가하지 않습니다.

기준: [Vite SPA 배포](https://vercel.com/docs/frameworks/frontend/vite#using-vite-to-make-spas), [외부 rewrite와 캐싱 비활성화](https://vercel.com/docs/routing/rewrites).

## 3. 실제 주소가 정해진 뒤 인증 설정

인증 시작과 콜백 모두 **같은 고정 프론트 도메인**을 사용해야 합니다. 콜백이 Render 주소로 돌아오면 프론트 도메인에서 시작한 세션 쿠키와 분리되어 인증이 실패할 수 있습니다. URL이 매번 바뀌는 PR Preview를 기본 인증 주소로 사용하지 않습니다.

실제 프론트 주소를 `https://<프로젝트>.vercel.app`이라고 할 때, Render의 다음 환경변수와 Google OAuth 클라이언트의 승인된 리디렉션 URI를 함께 맞춥니다. 아래는 기존 백엔드 계약에 따른 **설정 요청 목록**이며 이 변경에서 백엔드나 운영 설정을 수정하지 않습니다.

| Render 환경변수                           | 설정할 값                                                                    |
| ----------------------------------------- | ---------------------------------------------------------------------------- |
| `GOOGLE_REDIRECT_URI`                     | `https://<프로젝트>.vercel.app/api/v1/auth/callback/google`                  |
| `YOUTUBE_EXPORT_REDIRECT_URI`             | `https://<프로젝트>.vercel.app/api/v1/me/youtube/callback`                   |
| `YOUTUBE_FRONTEND_ORIGIN`                 | `https://<프로젝트>.vercel.app`                                              |
| `GOOGLE_REAUTHENTICATION_REDIRECT_URI`    | `https://<프로젝트>.vercel.app/api/v1/auth/callback/google-reauthentication` |
| `GOOGLE_REAUTHENTICATION_FRONTEND_ORIGIN` | `https://<프로젝트>.vercel.app`                                              |

Google OAuth 클라이언트에는 위 표의 콜백 URL 3개를 정확히 등록합니다. Origin 값에는 경로와 끝의 `/`를 붙이지 않습니다. 실제 배포 전 백엔드 담당자가 현재 설정과 사용 중인 OAuth 클라이언트를 확인합니다.

프론트의 `VITE_API_BASE_URL`을 Render URL로 바꾸거나 쿠키의 `Secure`·`HttpOnly`·`SameSite` 보호를 약화해 문제를 우회하지 않습니다. Google 로그인, YouTube 연결, 탈퇴 재인증의 중간 `Location`이 의도한 프론트 도메인을 벗어나는지도 확인합니다. Render 도메인으로 이동하는 문제가 있으면 프록시를 통과한 리디렉션 처리부터 확인하고 필요한 백엔드 수정을 별도 진행합니다.

## 4. 배포 후 확인

1. GitHub CI가 통과한 커밋과 Vercel의 배포 커밋이 일치하는지 확인합니다. GitHub 자동 배포와 CI 완료는 별개이므로 둘 다 확인합니다.
2. `/`, `/members`, `/songs`, `/login`, 존재하지 않는 화면 주소를 직접 열고 새로고침합니다. 화면·404가 표시되고 JS/CSS 요청이 HTML로 응답하지 않는지 확인합니다.
3. 같은 프론트 주소에서 `/api/v1/members?status=ALL&size=50`이 JSON을 반환하는지 확인합니다. `/api/v1/me`의 비로그인 응답은 JSON 401이어야 하며 HTML 200으로 바뀌면 안 됩니다. 응답 헤더에서 API가 캐시되지 않는지도 확인합니다.
4. 실제 Google 로그인 후 `/login` 복귀, `/me` 200, 새로고침 후 로그인 유지, CSRF를 사용하는 변경과 로그아웃을 확인합니다. 인증을 마치거나 취소한 뒤 화면 URL에 OAuth 코드·토큰이 남지 않아야 합니다.
5. YouTube 연결과 취소 후 계정 화면 복귀를 확인합니다. 탈퇴 재인증은 인증 후 확인 화면까지만 검증하고, 검증 목적으로 실제 계정을 삭제하지 않습니다.
6. 모바일 화면과 외부 프로필·썸네일·YouTube 임베드를 확인합니다. 프록시·쿠키·인증 리디렉션은 로컬 Vite preview만으로 검증 완료했다고 판단하지 않습니다.

첫 배포에 방문 분석 SDK나 광고를 추가하지 않습니다. 자체 광고는 향후에도 도입하지 않는 운영 방침입니다. 공개 전에는 별도 작업인 문서 최종 확정·공개 링크와 가입 안내를 완료해야 합니다. 이 배포 설정은 검토 중인 `frontend/legal/` 초안을 사이트 문서로 게시하지 않습니다.

## 5. 도메인 구매 후 `stelody.xyz` 연결

1. 도메인을 구매한 뒤 Vercel 프로젝트의 Settings → Domains에서 `stelody.xyz`를 추가합니다. 도메인 구매는 별도 절차이며 이 설정이 소유권을 확보하지 않습니다.
2. 구매 업체의 DNS 관리 화면에 Vercel이 해당 프로젝트에 안내하는 레코드를 입력합니다. 고정 IP 값을 문서에서 복사하지 말고 현재 프로젝트의 안내 값을 사용합니다.
3. 도메인 검증과 HTTPS 적용을 확인한 뒤 Render·Google의 위 인증 설정을 `https://stelody.xyz` 기준으로 함께 변경합니다.
4. `stelody.xyz`에서 4절의 인증·API 검증을 다시 수행합니다. 기존 Vercel 기본 도메인의 로그인 세션이 새 도메인으로 자동 이전되지는 않습니다.

실제 로그 항목·보관 기간과 Vercel의 처리 범위는 프로젝트 설정을 확인한 뒤 개인정보처리방침에 반영합니다. 대시보드에서 조회 가능한 로그 기간을 업체가 처리하는 모든 정보의 삭제 시점으로 단정하지 않습니다.
