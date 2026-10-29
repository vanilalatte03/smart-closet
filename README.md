# SmartCloset

SmartCloset은 날씨, 위치, 개인 옷장, 취향, 착용 이력을 바탕으로 오늘 입을 옷 조합을 추천하는 백엔드 중심 웹 서비스입니다.

Spring Boot 백엔드 프로젝트로서 단순 CRUD를 넘어 인증/세션, 사용자별 데이터 격리, 외부 날씨 API fallback, 규칙 기반 추천 도메인, 이미지 저장소 경계, AI-assisted 옷 등록 보조, Docker Compose 공유 실행까지 하나의 서비스 흐름 안에서 다룹니다. 프론트엔드는 백엔드 API를 실제 사용 흐름으로 검증하고 보여주기 위한 React SPA로 함께 둡니다.

## Overview

SmartCloset의 핵심 질문은 "오늘 날씨와 내 옷장 기준으로 왜 이 조합을 추천하는가?"입니다.

추천은 AI/GPT가 아니라 설명 가능하고 테스트 가능한 규칙 기반 로직으로 생성합니다. MVP10에서 AI는 옷차림 추천이 아니라 사진 기반 옷 등록 후보 제안에만 사용합니다. 사용자는 사진을 넣어 카테고리, 색상, 소재, 기온 범위, 비 적합성, style tag 후보를 빠르게 채운 뒤 confidence가 낮은 필드를 확인하고 저장합니다.

```text
회원가입/로그인
  -> 위치, 취향, 옷장 설정
  -> 사진 기반 AI 옷 등록 후보 체크
  -> KMA 날씨 또는 fallback weather 조회
  -> 규칙 기반 추천 생성
  -> 착용 기록과 피드백 저장
  -> 추천 이력 확인
```

## Tech Stack

- Backend: Java 21, Spring Boot 4.0.6, Spring Security, JPA
- AI assist: Spring AI 2.0.0 GA, OpenAI `gpt-5.4-nano`
- Database: MySQL, Flyway
- Frontend: React, Vite, TypeScript
- Auth: JWT bearer access token, DB-backed refresh session, HttpOnly refresh cookie
- Weather: KMA `getVilageFcst`, local fallback provider
- Observability: Spring Boot Actuator, Micrometer, Prometheus metrics, ECS structured logs, OpenTelemetry tracing boundary
- Runtime: Docker Compose, Nginx static frontend serving
- Storage: local file system, Docker Compose volume
- Tooling: Gradle, Docker Compose, project docs-check scripts

## Backend Highlights

| 영역 | 구현 포인트 | 설계에서 드러나는 것 |
| --- | --- | --- |
| 인증/계정 | JWT access token, DB-backed refresh session, HttpOnly refresh cookie, 이메일 인증, 비밀번호 재설정, Google OAuth, 로그인 실패 시도 제한 | 세션 안정성, token 원문 비저장, 계정 복구/삭제 흐름 |
| 사용자 데이터 격리 | 공개 `userId` query parameter 제거, 인증 principal 기준 보호 API | 실제 서비스형 multi-user API 설계 |
| 추천 도메인 | 날씨, 색상, 착용 이력, 추천 이력, 선호도, 피드백 기반 scoring, 생성 반복 호출 제한 | Controller/Repository 밖에 둔 테스트 가능한 domain logic |
| 옷 등록 AI 보조 | 보호 이미지 분석 API, Spring AI provider boundary, confidence/review DTO, 비용 제한 | AI를 저장/추천과 분리한 사용자 확인형 보조 흐름 |
| 날씨 연동 | KMA `getVilageFcst` provider, timeout/retry/circuit breaker, stale/static fallback, source snapshot | 외부 API 장애를 흡수하는 provider boundary |
| 위치 도메인 | KMA 행정구역 catalog 검색, 브라우저 좌표 resolve, GPS 원문 미저장 | 외부 지도 API 없이 생활권 위치를 다루는 방식 |
| 옷 이미지 | 별도 보호 이미지 API, 파일 검증, 로컬 파일 저장소, DB metadata 분리 | 파일 저장소와 소유권 검증 경계 |
| 운영 관측성 | Actuator health/prometheus endpoint, 추천/KMA/AI 분석 metric, Prometheus alert rule, Grafana dashboard baseline | 장애 원인 분리와 운영 확인 지점 |
| 운영 공유 | non-root app image, prod compose, Nginx frontend image, healthcheck, JVM memory env, MySQL backup/restore script, Docker Compose 실행 | 로컬 재현성과 운영 전환 준비 |

## Domain Structure

```text
com.smartcloset
├── auth              # signup/login, refresh session, email/password reset, Google OAuth
├── user              # current user profile, preferences, location ownership
├── clothing          # closet CRUD, archive/unarchive, image storage, AI registration assist
├── location          # KMA catalog search and browser coordinate resolve
├── weather           # weather provider abstraction, KMA adapter, fallback weather
├── recommendation    # outfit candidate generation, scoring, history, feedback
├── security          # JWT, authentication filter, security configuration
└── common            # response envelope, exception, shared entity/config
```

프론트엔드는 React/Vite/TypeScript SPA입니다. 백엔드 API를 실제 화면에서 검증하기 위한 클라이언트이기도 하며, 현재 MVP10에서는 옷 등록/수정 화면에 AI 후보 체크 흐름을 추가합니다.

## Architecture

SmartCloset은 HTTP, use case orchestration, domain rule, persistence/provider 책임을 분리합니다.

```text
Controller -> Application Service -> Domain Service -> Repository / Provider
```

- Controller는 HTTP 요청/응답, validation, 인증 principal 추출, DTO mapping을 담당합니다.
- Application Service는 use case 흐름과 transaction 경계를 조율합니다.
- Domain Service는 추천 후보 생성, 점수 계산, tie-break, 추천 이유 생성 같은 비즈니스 규칙을 담당합니다.
- Provider는 KMA weather, image storage, AI clothing analysis처럼 외부/인프라 경계를 감쌉니다.
- AI 분석은 `ClothingImageAnalyzer` 경계 뒤에 두고, 추천 domain service에는 연결하지 않습니다.

이 구조를 택한 이유는 추천 점수나 날씨 fallback 같은 핵심 규칙이 Controller 또는 Repository에 섞이면 테스트와 변경이 어려워지기 때문입니다. 현재 상세 구조는 `docs/ARCHITECTURE.md`를 source of truth로 둡니다.

## Key Decisions

**인증 사용자 API로 전환**

초기 MVP의 테스트용 `userId` query parameter를 제거하고, Spring Security principal 기준으로 현재 사용자를 식별합니다. 이 결정은 사용자별 옷장, 위치, 취향, 추천 이력을 실제 서비스처럼 격리하기 위한 기반입니다. 관련 결정은 ADR-008에 정리되어 있습니다.

**Access token + HttpOnly refresh cookie**

Access token은 bearer token으로 유지하되, refresh token은 HttpOnly cookie로만 전달하고 DB에는 hash만 저장합니다. 새로고침 복구, token rotation, logout revoke, 만료 UX를 다루기 위해 MVP8에서 DB-backed refresh session을 추가했습니다. 관련 결정은 ADR-013을 따릅니다.

**AI가 아니라 규칙 기반 추천**

추천은 날씨, 색상, 착용/추천 이력, 선호도, 최근 피드백을 기반으로 계산합니다. 추천 이유도 template 기반으로 생성합니다. 대형 옷장은 날씨 필터 이후 category별 후보 pool 예산으로 계산량을 제한하고, 추천 생성 command는 user별 process-local throttle로 반복 호출을 제한합니다. MVP10의 AI 분석 결과는 옷 등록 후보를 채우는 데만 쓰이며 추천 점수, 후보 pool 선정, tie-break, 추천 이유에 관여하지 않습니다.

**사진 기반 옷 등록 보조**

MVP10은 Spring AI와 OpenAI `gpt-5.4-nano`로 사진을 분석해 옷 등록 후보와 confidence를 반환합니다. confidence가 낮은 필드는 사용자가 확인하거나 수정해야 하며, 확인된 값만 기존 옷 저장 API로 저장합니다. 관련 결정은 ADR-016입니다.

**KMA provider와 fallback 분리**

실제 날씨 추천을 위해 KMA 단기예보 `getVilageFcst`를 사용하지만, API key 미설정, 네트워크 실패, NODATA, 응답 누락이 있어도 Docker Compose 데모가 깨지지 않도록 fallback provider를 유지합니다. 관련 결정은 ADR-006, ADR-012에 있습니다.

**이미지는 DB가 아니라 저장소에 둠**

옷 JSON API를 multipart로 바꾸지 않고 이미지 업로드/조회/삭제를 별도 보호 API로 분리했습니다. 파일 bytes는 파일 시스템 또는 volume에 저장하고 DB에는 metadata만 둡니다. AI 분석용 이미지는 후보 제안에만 쓰고 저장하지 않습니다.

업로드와 AI 분석 전에 실제 이미지 디코딩과 2천만 픽셀 제한을 확인해 손상 파일을 거절합니다. 파일 형식과 오류 응답은 [이미지 API 계약](docs/API.md#clothing-image-api)을 따릅니다.

**Actuator와 Prometheus 기반 운영 baseline**

운영 준비 범위에서는 Spring Boot Actuator의 health/prometheus endpoint를 열고, 추천 생성, KMA provider, OpenAI 옷 분석 provider outcome과 duration을 Micrometer metric으로 기록합니다. Alertmanager receiver나 외부 SaaS 연동은 포함하지 않고, `monitoring/` 아래 Prometheus alert rule과 Grafana dashboard baseline으로 운영 확인 지점을 먼저 고정합니다. 관련 결정은 ADR-018입니다.

**Container와 DB 운영 하드닝 baseline**

운영 준비 범위에서는 app runtime image를 non-root user로 실행하고, Dockerfile healthcheck를 Actuator health endpoint에 연결합니다. Docker Compose는 app 시작 전에 `clothing-image-data` volume 소유권을 UID/GID `10001:10001`로 보정합니다. JVM container memory 비율은 `JAVA_TOOL_OPTIONS`로 조정하며, MySQL backup/restore는 로컬 검증 가능한 `scripts/mysql-backup.sh`, `scripts/mysql-restore.sh` runbook으로 시작합니다. 관련 결정은 ADR-019입니다.

**Prod runtime 산출물**

운영 배포 준비 범위에서는 local/demo `docker-compose.yml`과 별도로 `docker-compose.prod.yml`을 둡니다. Prod compose는 Spring `prod` profile, Flyway/validate schema, secure refresh/OAuth state cookie, Swagger/OpenAPI 비활성 기본값, 필수 secret/env 검사를 사용합니다. Frontend는 Vite dev server가 아니라 `frontend/Dockerfile`의 Nginx static image로 서빙합니다. 관련 결정은 ADR-020입니다.

**CI 보안 게이트**

PR CI는 기존 테스트/빌드에 더해 frontend `npm audit`, Trivy dependency scan, backend/frontend image build, Trivy image scan을 실행합니다. Fix 가능한 high/critical 취약점은 실패 처리하고, scan 예외가 필요하면 문서화된 별도 변경으로 다룹니다. 관련 결정은 ADR-021입니다.

**CI 품질 게이트**

PR CI는 backend Checkstyle, JaCoCo line coverage 60% verification, frontend ESLint, frontend Vitest를 실행합니다. 초기 기준은 현재 테스트 자산을 기준으로 한 운영 하한이며, 기능과 테스트가 늘어나면 별도 PR에서 상향합니다. 관련 결정은 ADR-022입니다.

## Current MVP

현재 문서 기준은 **MVP10: AI 옷 등록 보조 MVP**입니다.

MVP10은 MVP9 UI/UX 리디자인 완료 상태 위에서 사진 업로드 기반 옷 등록 후보 체크를 추가합니다. 사용자는 AI가 제안한 후보 중 confidence가 낮은 필드를 확인/수정한 뒤 기존 저장 흐름으로 옷을 등록합니다. 추천 생성은 계속 규칙 기반으로 유지되며, DB schema와 추천 점수/tie-break는 변경하지 않습니다. 후보 pool 예산은 사진 분석 결과와 이미지 metadata를 사용하지 않는 성능 정책입니다.

MVP가 바뀔 때 README에서 주로 갱신하는 위치는 이 섹션과 `Documentation`의 MVP-specific 링크입니다. 상세 제품 범위는 `docs/PRD.md`, API 계약은 `docs/API.md`, 추천 규칙은 `docs/RECOMMENDATION_RULES.md`를 우선합니다.

## Getting Started

로컬 백엔드:

```bash
./gradlew bootRun
```

로컬 프론트엔드:

```bash
cd frontend
npm run dev
```

Docker Compose:

```bash
test -f .env || cp .env.example .env
docker compose up --build
```

Prod compose smoke:

```bash
scripts/prod-compose-smoke.sh
```

MVP10 AI 분석은 기본 비활성입니다. 실제 OpenAI 호출을 확인하려면 `docs/SHARING_GUIDE.md`의 AI 옷 등록 보조 환경변수 섹션을 따른다.

자세한 명령과 Docker Compose smoke 절차는 `docs/COMMANDS.md`를 따릅니다.

## Verification

문서 변경:

```bash
git diff --check
python3 scripts/checks.py --docs-check-config phases/10-smartcloset-ai-clothing-assist/docs-checks.json --docs-check
```

백엔드 변경:

```bash
./gradlew test
./gradlew test --tests com.smartcloset.persistence.SchemaMigrationSmokeTest
./gradlew build
```

프론트 변경:

```bash
cd frontend
npm run build
```

## Documentation

| 영역 | Source of truth |
| --- | --- |
| 제품 목표, MVP 범위, 포함/제외 | `docs/PRD.md` |
| HTTP API, 인증 경계, DTO, 에러 코드 | `docs/API.md` |
| 추천 후보, 점수, tie-break, 추천 이유 | `docs/RECOMMENDATION_RULES.md` |
| 백엔드 구조, 저장소, 트랜잭션 | `docs/ARCHITECTURE.md` |
| DB schema, entity/JPA 기준 | `docs/ERD.md` |
| 프론트 타입, API client, UX, 반응형 기준 | `docs/FRONTEND.md` |
| 데모와 수동 검증 | `docs/DEMO_SCENARIO.md` |
| Docker Compose 공유와 환경변수 | `docs/SHARING_GUIDE.md` |
| 실행 명령과 검증 명령 | `docs/COMMANDS.md` |
| MVP 변경 체크리스트 | `docs/MVP_CHANGE_CHECKLIST.md` |
| 결정 기록과 변경 이력 | `docs/ADR.md`, `docs/adr/` |
