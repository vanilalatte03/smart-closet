# 아키텍처: SmartCloset MVP10

## 전체 아키텍처 개요

SmartCloset MVP10은 Spring Boot 4.0.6 백엔드와 React+Vite+TypeScript 프론트엔드 앱으로 구성한다. MVP10의 변경 지점은 사진 기반 AI 옷 등록 보조다.

MVP10은 Spring AI 2.0.0 GA와 OpenAI `gpt-5.4-nano`를 `ClothingImageAnalyzer` provider boundary 뒤에 둔다. AI는 옷 등록 후보를 제안할 뿐이며, 옷차림 추천 domain service, 추천 점수, 추천 이유, 추천 이력에는 연결하지 않는다.

기존 위치/날씨, 옷 이미지, 추천 피드백/개인화, 추천 이력, MVP8 account/auth 구조와 MVP9 UI/UX 리디자인 흐름은 유지한다. MVP10 AI 옷 등록 보조는 DB schema를 변경하지 않는다.

```text
Controller -> Application Service -> Domain Service -> Repository / Provider
```

추천 점수 계산은 recommendation domain service에 둔다. Controller와 Repository에는 점수 계산 로직을 두지 않는다.

## 권장 패키지 구조

```text
com.smartcloset
├── auth
│   ├── application
│   ├── domain
│   ├── dto
│   ├── infrastructure
│   └── controller
├── account
│   ├── application
│   ├── domain
│   ├── dto
│   └── controller
├── common
├── security
├── user
├── location
├── weather
├── clothing
│   ├── application
│   ├── controller
│   ├── domain
│   ├── dto
│   └── infrastructure
│       ├── image
│       └── analysis
└── recommendation
```

MVP10 clothing analysis 구성 요소:

- `ClothingImageAnalyzer`: 옷 사진 분석 provider interface
- `SpringAiClothingImageAnalyzer`: Spring AI `ChatClient` 기반 OpenAI analyzer
- `DisabledClothingImageAnalyzer`: 기능 비활성 또는 API key 없음 상태의 local 기본 analyzer
- `ClothingAnalysisService`: validation, limit, analyzer 호출, DTO mapping 조율
- `ClothingAnalysisProperties`: 활성 여부, model, timeout, low confidence threshold, daily limit 설정
- `ClothingAnalysisDailyLimiter`: user별 in-memory 일일 호출 제한
- `ClothingAnalysisResponse`, `ClothingAnalysisSuggestion`: API response DTO
- `RecommendationCreationThrottle`: 추천 생성 command의 user별 process-local fixed-window 반복 호출 제한

프론트엔드:

```text
frontend/src
├── api
├── components
├── features
│   ├── auth
│   ├── account
│   ├── clothes
│   ├── location
│   ├── preferences
│   ├── recommendation
│   └── history
├── types
└── main.tsx
```

## 인증 경계

공개 API:

- `POST /api/auth/signup`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `POST /api/auth/email-verification/request`
- `POST /api/auth/email-verification/confirm`
- `POST /api/auth/password-reset/request`
- `POST /api/auth/password-reset/confirm`
- `GET /api/auth/oauth2/providers`
- `GET /api/auth/oauth2/google`
- `GET /api/auth/oauth2/callback/google`

그 외 `/api/**` endpoint는 보호 API다. `POST /api/clothes/analyze-image`도 보호 API이며 `Authorization: Bearer {accessToken}`이 필요하다.

모든 사용자 소유 데이터는 인증 principal의 현재 사용자 id로 제한한다. 공개 `userId` query parameter를 되살리지 않는다.

## 오류 처리와 운영 로그

Controller 이후로 전파된 HTTP API 예외는 `GlobalExceptionHandler`가 `ErrorCode` 기반 JSON 실패 응답으로 변환한다. Spring Security filter 단계의 인증/인가 실패는 `SecurityErrorResponseWriter`가 같은 실패 응답 구조로 변환한다.

실패 응답을 만든 서버 로그는 운영 추적을 위해 `code`, `status`, HTTP method, request path, exception class, 고정된 error message를 남긴다. 민감정보 노출을 막기 위해 request body, Authorization header, cookie, query string, raw exception message, token/action token/API key/password는 로그에 남기지 않는다.

## 운영 관측성

운영 관측성 baseline은 Spring Boot Actuator, Micrometer Prometheus registry, Spring Boot structured logging, Spring Boot OpenTelemetry starter를 사용한다. 기본 local endpoint 노출은 `health`, `info`, `prometheus`이고, `prod` profile 기본 노출은 `health`, `prometheus`다. health detail은 기본적으로 숨긴다.

```text
GET /actuator/health
GET /actuator/prometheus
```

Custom metric은 `SmartClosetMetrics`가 이름과 low-cardinality tag를 관리한다.

| Metric | Type | Tags | 목적 |
| --- | --- | --- | --- |
| `smartcloset.recommendation.requests` | counter | `situation`, `forecast_period`, `outcome` | 추천 생성 success/failure/limit 비율 |
| `smartcloset.recommendation.duration` | timer | `situation`, `forecast_period`, `outcome` | 추천 생성 latency |
| `smartcloset.weather.provider.requests` | counter | `provider`, `forecast_period`, `outcome` | KMA success/fallback/failure/cache hit/stale fallback 비율 |
| `smartcloset.weather.provider.duration` | timer | `provider`, `forecast_period`, `outcome` | KMA provider latency |
| `smartcloset.clothing.analysis.requests` | counter | `provider`, `outcome` | OpenAI 옷 분석 success/not analyzable/disabled/unavailable/limit/invalid request 비율 |
| `smartcloset.clothing.analysis.duration` | timer | `provider`, `outcome` | 옷 분석 provider latency |

Prometheus export에서는 Micrometer 이름이 snake_case로 변환된다. 예를 들어 `smartcloset.recommendation.requests`는 `smartcloset_recommendation_requests_total`, timer histogram은 `smartcloset_recommendation_duration_seconds_bucket` 형태로 조회한다.

`monitoring/prometheus/alerts.yml`은 추천 실패율, 추천 p99 latency, KMA fallback/failure/cache hit fallback/stale fallback 비율, OpenAI 분석 장애 비율, HikariCP pool saturation, JVM heap 사용률 alert baseline을 둔다. `monitoring/prometheus/prometheus.yml`은 local Alertmanager 예시 target을 `host.docker.internal:9093`으로 둔다. `monitoring/alertmanager/alertmanager.yml`은 실제 webhook 없이 local null receiver만 둔다. `monitoring/grafana/smartcloset-dashboard.json`은 Prometheus datasource를 연결해 import하는 dashboard baseline이다.

Metric tag에는 user id, token, image path, raw exception message, provider secret을 넣지 않는다. 실제 운영 배포에서는 `/actuator/prometheus`를 공개 인터넷에 직접 노출하지 않고 Prometheus가 접근하는 내부 네트워크나 proxy allowlist 뒤에 둔다. 현재 범위에는 외부 알림 채널, log aggregation backend 운영, vendor-specific error tracking SDK, AWS/RDS/Secrets Manager 통합을 추가하지 않는다.

Structured log와 tracing 정책:

- Console log format 기본값은 Spring Boot built-in ECS JSON(`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`)이다.
- ECS `service.name`, `service.version`, `service.environment`는 app/env property에서 채운다.
- API/business/security error log는 SLF4J key-value field로 `code`, `status`, `method`, `path`, `exception`, 고정 `error_message`를 남긴다.
- Request body, Authorization header, cookie, query string, raw exception message, token/action token/API key/password는 로그 field와 message에 남기지 않는다.
- Micrometer tracing sampling 기본값은 `1.0`이며 `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`로 조정한다.
- OTLP trace export는 `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=true`일 때 `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`로 지정한 collector에 보낸다. 기본 local/demo 실행은 OTLP trace/log/metrics export를 비활성으로 둬 collector 없이도 실행 가능해야 한다.
- `TraceIdResponseFilter`는 현재 span이 있을 때 `X-Trace-Id` response header를 추가한다.
- Sentry 같은 vendor-specific error tracking SDK는 이번 baseline에 넣지 않는다. 에러 추적은 structured error log, `X-Trace-Id`, OTLP trace export로 시작하고, Sentry/Datadog 등은 운영 backend 선택과 secret/scrubbing 정책이 확정될 때 별도 ADR로 추가한다.

## 옷 사진 분석 흐름

```text
POST /api/clothes/analyze-image
  -> ClothingController
  -> ClothingAnalysisService.analyze(currentUserId, multipart image)
      -> 기존 이미지 MIME/size validation 재사용
      -> user별 daily limit 확인
      -> ClothingImageAnalyzer.analyze(image resource, mime type)
      -> structured response 검증
      -> confidence threshold로 reviewRequiredFields 계산
  -> ClothingAnalysisResponse 반환
```

정책:

- multipart part 이름은 `image`다.
- 허용 파일은 기존 옷 이미지 API와 같은 5MB 이하 jpg/jpeg/png/webp다.
- 업로드/교체와 분석은 `ClothingImageValidator`를 공유한다. 확장자/MIME/signature, 종료 marker 또는 RIFF 길이, 첫 이미지의 실제 디코딩을 확인하며 디코더 오류/경고는 `INVALID_REQUEST`로 변환한다.
- 디코딩 전 가로 × 세로를 20,000,000픽셀로 제한한다. JPEG/PNG는 JDK ImageIO, WebP lossy/lossless는 TwelveMonkeys `imageio-webp` 플러그인을 사용한다.
- 검증은 `MemoryCacheImageInputStream`을 사용해 ImageIO 검증용 디스크 캐시를 만들지 않는다. 검증은 원본 bytes를 변환하거나 리사이즈하지 않는다.
- 이미지 검증은 분석 일일 제한 차감과 provider 호출 전에 완료한다. 검증 실패 시 기존 이미지 파일과 metadata를 변경하지 않는다.
- 분석 이미지는 DB나 파일 저장소에 저장하지 않는다.
- 분석 결과도 DB, 추천 이력, 옷 이미지 metadata에 저장하지 않는다.
- 옷으로 보기 어려운 사진은 `analyzable=false` 성공 응답으로 처리한다.
- `suggestion`은 기존 `ClothingRequest` field와 같은 후보값이다.
- `fieldConfidence` 값은 0.0 이상 1.0 이하 숫자다.
- `reviewRequiredFields`는 confidence가 threshold보다 낮거나 모델이 확인 필요로 판단한 field 이름이다.
- provider 장애, timeout, 비활성, limit 초과는 `docs/API.md`의 error code를 따른다.

## Spring AI provider boundary

Spring AI는 OpenAI 호출을 감싸는 adapter로만 사용한다.

기본 설정:

```yaml
spring:
  ai:
    model:
      chat: ${SPRING_AI_MODEL_CHAT:none}
    openai:
      api-key: ${OPENAI_API_KEY:}
      chat:
        options:
          model: ${CLOTHING_ANALYSIS_MODEL:gpt-5.4-nano}

smartcloset:
  clothing:
    analysis:
      enabled: ${CLOTHING_ANALYSIS_ENABLED:false}
      low-confidence-threshold: ${CLOTHING_ANALYSIS_LOW_CONFIDENCE_THRESHOLD:0.75}
      daily-limit: ${CLOTHING_ANALYSIS_DAILY_LIMIT:20}
      timeout-seconds: ${CLOTHING_ANALYSIS_TIMEOUT_SECONDS:10}
      max-attempts: ${CLOTHING_ANALYSIS_MAX_ATTEMPTS:2}
      retry-backoff: ${CLOTHING_ANALYSIS_RETRY_BACKOFF:200ms}
      circuit-breaker-failure-threshold: ${CLOTHING_ANALYSIS_CIRCUIT_BREAKER_FAILURE_THRESHOLD:3}
      circuit-breaker-open-duration: ${CLOTHING_ANALYSIS_CIRCUIT_BREAKER_OPEN_DURATION:30s}
```

정책:

- `CLOTHING_ANALYSIS_ENABLED=false`가 기본값이다.
- `SPRING_AI_MODEL_CHAT=none`과 빈 API key 상태에서도 앱 시작과 기존 기능이 깨지지 않아야 한다.
- 실제 OpenAI 호출은 기능 활성, chat model 활성, API key 존재가 모두 충족될 때만 가능하다.
- 기본 모델은 `gpt-5.4-nano`다.
- OpenAI provider 호출은 application timeout 안에서 Resilience4j retry와 process-local circuit breaker를 적용한다.
- GPT-5 계열 temperature 미지원 가능성을 고려해 temperature를 강제로 설정하지 않는다.
- 다른 모델로 자동 재시도하지 않는다.
- 응답은 짧은 structured output으로 제한한다.
- system/user prompt에는 저장 가능한 후보 field와 enum 후보만 전달한다.

## 비용 방어

MVP10은 사용자가 사진을 선택했다고 자동으로 OpenAI를 호출하지 않는다.

- 분석은 프론트의 수동 `AI 후보 체크` command에서만 실행한다.
- user별 in-memory daily limit 기본값은 20회이며, 날짜가 바뀌면 이전 날짜 counter를 process-local map에서 정리한다.
- 프론트는 파일 fingerprint 기준으로 같은 파일의 마지막 분석 결과를 재사용할 수 있다.
- provider 장애 시 더 비싼 모델로 자동 재시도하지 않는다.
- API key와 실제 secret은 코드와 문서 예시에 커밋하지 않는다.

## 저장 경계

옷 저장은 기존 JSON API를 유지한다.

```text
POST /api/clothes
PUT /api/clothes/{clothingId}
```

옷 이미지는 기존 별도 보호 multipart API를 유지한다.

```text
PUT /api/clothes/{clothingId}/image
GET /api/clothes/{clothingId}/image
DELETE /api/clothes/{clothingId}/image
```

AI 분석은 저장 API가 아니다. 사용자가 확인/수정한 최종 form 값만 기존 옷 JSON 저장 API로 저장하고, 이미지 저장은 기존 이미지 API로 분리한다.

## 기존 계정/auth 흐름 유지

MVP8에서 추가한 auth/account 개념은 MVP10에서도 유지한다.

- `RefreshSession`
- `RefreshTokenService`
- `RefreshTokenCookieProperties`
- `AccountActionToken`
- `AccountActionTokenPurpose`: `EMAIL_VERIFICATION`, `PASSWORD_RESET`
- `EmailSender`
- `ConsoleEmailSender`
- `SocialAccount`
- `OAuthProvider`: `GOOGLE`
- `AccountDeletionService`

정책:

- Raw refresh token은 DB에 저장하지 않는다.
- Refresh token hash는 고정된 서버 secret 또는 secure digest 정책으로 생성한다.
- Refresh token rotation은 매 refresh 요청마다 수행한다.
- Reused/revoked token은 `INVALID_TOKEN`으로 실패한다.
- Logout은 멱등이며 cookie를 만료한다.
- Password signup은 access token을 발급하지 않는다.
- 미인증 password 계정은 login할 수 없다.
- password login 실패는 정규화 email과 servlet remote address 조합 및 servlet remote address 단독 process-local in-memory window로 제한한다.
- `X-Forwarded-For` 등 proxy header는 신뢰 가능한 proxy 경계가 없는 현재 MVP10 local/API 범위에서 login attempt key로 사용하지 않는다.
- Token 원문은 DB에 저장하지 않고 hash만 저장한다. Local/demo action token 원문 확인은 `SMARTCLOSET_EMAIL_OUTBOX_PATH` outbox 파일로만 수행한다.
- Google provider 설정이 없으면 provider status는 disabled다.
- 계정 삭제는 현재 사용자 소유 데이터와 이미지 파일을 즉시 hard delete한다.

## 후속 배포 adapter boundary

MVP10은 AWS 배포를 구현하지 않는다. local Docker Compose 실행과 기존 adapter boundary를 유지한다.

- `EmailSender`는 interface로 두고 현재 local 구현체는 `ConsoleEmailSender`다. `ConsoleEmailSender`는 action token 원문을 SLF4J 로그에 남기지 않고 `SMARTCLOSET_EMAIL_OUTBOX_PATH` local outbox 파일에만 쓴다.
- 후속 MVP에서 SES/SMTP sender를 추가해도 auth application service는 바꾸지 않는다.
- `ClothingImageStorage`는 기존 local file 구현을 유지한다.
- 후속 MVP에서 S3 구현체를 추가해도 account deletion service는 storage interface만 사용한다.
- Cookie, CORS, OAuth URL, AI 분석 설정은 properties/env로 분리한다.
- `local` profile은 Docker Compose 기본 실행 경로로 유지한다.
- demo user와 최소 옷장 seed initializer는 `local`/`demo` profile과 `smartcloset.seed.enabled=true` 조건에서만 활성화한다. default/prod profile 기동은 seed 데이터를 자동 생성하지 않는다.
- Flyway baseline migration은 깨끗한 DB에서 현재 schema를 생성하고, Hibernate `ddl-auto=validate`가 entity/schema drift를 검증한다.
- `local`/`demo` profile은 기존 로컬 volume 편입을 위해 Flyway `baseline-on-migrate=true`를 기본값으로 둘 수 있다.
- `prod` profile은 local JWT secret placeholder와 Hibernate `ddl-auto=update`를 허용하지 않고, Swagger UI/API docs를 기본 비활성화한다.
- prod runtime은 `docker-compose.prod.yml`로 local/demo compose와 분리하고, frontend는 `frontend/Dockerfile`의 Nginx static image로 서빙한다.
- prod compose는 `smartcloset-prod` project name과 명시적 prod volume name으로 local/demo Docker volume과 충돌하지 않게 한다. Smoke 실행은 volume name을 임시 project prefix로 override한다.
- prod profile은 refresh cookie와 OAuth state cookie `Secure=true`를 요구하며, `ProdProfileSafetyGuard`가 insecure cookie 설정을 fail-fast로 막는다.
- app runtime Docker image는 UID/GID `10001:10001` non-root user로 실행한다.
- Dockerfile healthcheck는 `SMARTCLOSET_HEALTHCHECK_URL` 기본값 `http://127.0.0.1:8080/actuator/health`를 사용한다.
- JVM container memory option은 `JAVA_TOOL_OPTIONS`로 주입하며 local 기본값은 `-XX:MaxRAMPercentage=75.0`이다.
- PR CI는 backend/frontend dependency와 Docker image를 Trivy `HIGH,CRITICAL` 기준으로 스캔하고, backend/frontend production image build를 merge gate로 둔다.
- PR CI는 backend Checkstyle, JaCoCo line coverage 60% verification, frontend ESLint, frontend Vitest를 품질 gate로 둔다.
- Docker Compose는 `clothing-image-volume-permissions` one-shot service로 app 시작 전에 `clothing-image-data` volume 소유권을 UID/GID `10001:10001`로 보정한다.
- MySQL backup/restore는 `scripts/mysql-backup.sh`, `scripts/mysql-restore.sh`로 local 검증 가능한 runbook을 유지한다. backup dump는 `backups/` 아래에 생성할 수 있으며 git/docker build context에 포함하지 않는다.

## 기존 domain 흐름 유지

- 위치 검색과 좌표 resolve는 MVP7 계약을 유지한다.
- Weather provider는 KMA `getVilageFcst`와 fallback만 사용한다.
- KMA provider cache는 process-local bounded TTL cache이며, 날씨 값/source만 공유하고 사용자 위치 snapshot은 응답 시점에 합성한다.
- KMA HTTP connect/read/request timeout은 env로 조정하며, KMA client에는 Resilience4j retry와 process-local circuit breaker를 적용한다.
- KMA refresh 실패 시 같은 grid와 forecastPeriod의 마지막 정상 KMA 날씨 값이 `KMA_STALE_CACHE_TTL` 기본 30분 안에 있으면 static fallback보다 먼저 stale fallback으로 사용하고 `stale_cache_fallback` metric을 기록한다. raw KMA 응답 JSON은 저장하지 않는다.
- 추천 생성은 `POST /api/recommendations`이며 optional `situation`, `forecastPeriod`를 받는다.
- 추천 생성은 user별 process-local fixed-window throttle을 먼저 통과해야 하며, 기본 정책은 1분 window 안에서 user당 30회다.
- 추천 결과와 이력의 위치/날씨 source snapshot은 유지한다.
- 옷 이미지 API는 보호 API이며 blob fetch에 Authorization header가 필요하다.
- 추천 피드백 PUT은 전체 교체이고 누락 필드는 `null`이다.
- AI 분석 결과는 recommendation domain service 입력이 아니다.
- Image metadata도 scoring, 후보 pool 선정, tie-break, recommendation reason에 사용하지 않는다.
- 대형 옷장 추천 계산은 날씨 필터 이후 category별 후보 pool 예산으로 조합 수를 제한한다.

## 트랜잭션 경계

- Signup: user/action token 생성 write transaction, email sending과 신규 계정 기본 옷 onboarding은 `afterCommit` 예약으로 commit 이후 실행
- Email verification request: 미인증 user lookup + action token 생성 write transaction, email sending은 `afterCommit` 예약으로 commit 이후 실행
- Password reset request: password-enabled user lookup + action token 생성 write transaction, email sending은 `afterCommit` 예약으로 commit 이후 실행
- Login: process-local attempt throttle 확인, user read, refresh session issue write transaction. 기본 옷 seed/onboarding은 수행하지 않음
- Refresh: refresh session rotation write transaction. 기본 옷 seed/onboarding은 수행하지 않음
- Logout: refresh session revoke write transaction 또는 멱등 no-op
- Email verification confirm: action token consume + user update write transaction
- Password reset confirm: action token consume + password update + refresh revoke write transaction
- OAuth callback: Google provider 호출은 transaction 밖에서 수행하고, user/social account upsert + refresh issue만 write transaction. 새 Google user 기본 옷 onboarding은 commit 이후 실행. Known user/social unique 충돌은 provider 재호출 없이 새 transaction에서 재조회해 로그인으로 수렴
- Account deletion: current user owned data delete write transaction, image file cleanup은 명시적 보상 정책 필요
- Clothing create/update: current user owned data write transaction
- Clothing image upload/delete: metadata write transaction과 file cleanup 정책 분리
- Clothing image analysis: 인증 사용자 확인, validation, limit 확인, provider 호출을 수행하되 옷/추천 DB row를 만들지 않는다.

## 금지사항

- AWS 배포 구현을 추가하지 않는다. 이유: MVP10은 AI 옷 등록 보조 MVP이며 AWS는 후속 범위다.
- S3 구현체를 추가하지 않는다. 이유: 현재는 `ClothingImageStorage` 경계만 보존한다.
- SES/SMTP 실제 발송 구현체를 추가하지 않는다. 이유: 현재 이메일은 local outbox 기반 `ConsoleEmailSender` 기준이다.
- Redis를 추가하지 않는다. 이유: refresh session, login attempt throttle, 분석 daily limit은 현재 범위에서 DB-backed 또는 in-memory로 검증한다.
- 추천 생성 반복 호출 제한은 현재 process-local throttle로 검증한다. 이유: Redis/DB-backed distributed limiter는 운영 adapter 확정 후 별도 범위로 추가한다.
- AI/GPT 옷차림 추천을 추가하지 않는다. 이유: 추천은 규칙 기반 계약이다.
- AI-generated 추천 이유를 추가하지 않는다. 이유: 추천 이유는 template 기반이다.
- 이미지 기반 추천 score, filtering, tie-break를 추가하지 않는다. 이유: 이미지와 분석 결과는 등록 보조 전용이다.
- 사용자 확인 없는 자동 저장이나 자동 태깅을 추가하지 않는다. 이유: 최종 저장값은 사용자가 확인해야 한다.
- 분석 이미지를 저장하지 않는다. 이유: MVP10은 후보 제안만 다룬다.
- 분석 결과 전용 DB 구조를 추가하지 않는다. 이유: MVP10 AI 분석 결과는 저장하지 않고, schema 변경은 Flyway migration으로 명시 추적해야 한다.
- 다른 모델로 자동 재시도하는 흐름을 추가하지 않는다. 이유: 비용 예측 가능성을 유지해야 한다.
- 공개 `userId` query parameter를 추가하지 않는다. 이유: 인증 사용자 API 계약과 충돌한다.
- Refresh token 원문을 DB 또는 JSON 응답에 저장/노출하지 않는다. 이유: 계정 안정성 핵심 보안 계약이다.
