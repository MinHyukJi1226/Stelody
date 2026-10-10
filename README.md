# Stelody

**스텔라이브의 노래를 한곳에서 만나고, 좋아하는 곡을 나만의 목록으로 모으는 음악 데이터베이스입니다.**

Stelody는 스텔라이브의 커버곡, 오리지널곡, 콜라보곡을 모아 소개하는 프로젝트입니다. 여러 채널에 흩어진 노래와 관련 정보를 연결해, 스텔라이브의 음악을 더 쉽게 찾고 살펴볼 수 있는 공간을 만들고자 합니다.

## 프로젝트 소개

좋아하는 멤버의 노래를 찾아 듣거나 새로운 곡을 발견할 때, 영상과 곡 정보를 여러 곳에서 따로 찾아야 하는 번거로움을 줄이는 것이 Stelody의 출발점입니다.

곡과 참여 멤버, 원곡 등 노래에 관한 정보를 함께 살펴보고, 조회수의 흐름을 통해 곡이 쌓아 온 기록을 확인할 수 있는 서비스를 지향합니다. 마음에 드는 곡은 즐겨찾기로 모으고, 취향과 분위기에 맞는 플레이리스트로 정리할 수 있도록 합니다.

Stelody는 노래 정보를 모으는 데서 나아가, 익숙한 곡을 다시 만나고 아직 몰랐던 곡을 발견하는 공간을 목표로 합니다.

## 기술 구성

React와 TypeScript 기반의 웹 화면, Java와 Spring Boot 기반의 서버, PostgreSQL 데이터베이스를 중심으로 구성하는 프로젝트입니다.

| 영역 | 기술 |
| --- | --- |
| 프론트엔드 | React, Vite, TypeScript |
| UI 및 상태 관리 | Tailwind CSS, shadcn/ui, React Router, TanStack Query |
| 백엔드 | Java 21, Spring Boot, Spring MVC, Gradle Wrapper |
| 인증 및 세션 | Spring Security, Spring Session JDBC |
| 데이터베이스 | PostgreSQL, Spring Data JPA, Flyway |
| 로컬 환경 | Docker Compose, Mailpit |
| 백엔드 검증 | JUnit, MockMvc, Testcontainers, Spotless |

## 저장소 구성

- `backend/`: Spring Boot 애플리케이션, DB 마이그레이션, 백엔드 테스트
- `infra/`: 로컬 PostgreSQL·Mailpit, 운영 실행 구성과 백업·복구 도구
- `frontend/`: React 웹 애플리케이션, UI 컴포넌트, 프론트엔드 테스트
- `AGENTS.md`: 개발 절차, 커밋 및 브랜치 규칙

실행·인증·API 연동·운영 안내는 [백엔드 README](backend/README.md)를 참고하세요.
