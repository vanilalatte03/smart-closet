# Commands

이 파일은 프로젝트 실행 명령의 단일 출처입니다.

현재는 Java 21 Spring Boot 4.0.6 + Gradle + Docker Compose + React/Vite/TypeScript frontend 기준입니다. MVP10은 MVP9 UI/UX 리디자인 완료 baseline 위에서 Spring AI 기반 사진 업로드 옷 등록 보조를 추가합니다.

## 개발 전 준비

```bash
python3 -m pip install -r requirements-dev.txt
git config core.hooksPath .githooks
```

## 활성 명령

| 이름 | 명령 | 필수 | 설명 |
| --- | --- | --- | --- |
| dev | `./gradlew bootRun` | no | 백엔드 로컬 개발 서버 실행 |
| frontend-dev | `cd frontend && npm run dev` | no | 프론트엔드 Vite 개발 서버 실행 |
| frontend-lint | `cd frontend && npm run lint` | yes | ESLint 기반 frontend 정적 분석 |
| frontend-test | `cd frontend && npm test` | yes | Vitest 기반 frontend 테스트 |
| frontend-build | `cd frontend && npm run build` | yes | TypeScript type check 및 Vite build |
| frontend-audit | `cd frontend && npm audit --audit-level=high` | yes | 프론트엔드 dependency high 이상 취약점 확인 |
| lint | `python3 -m compileall scripts` | no | Harness 운영 스크립트 문법 검사 |
| test | `./gradlew test` | yes | Spring Boot/JUnit 테스트 실행 |
| recommendation-benchmark | `./gradlew recommendationBenchmark` | no | DB/외부 API 없이 추천 계산 시간과 누적 객체 할당량 비교. [측정 방법](performance/recommendation-scoring-context.md) 참고 |
| backend-static-analysis | `./gradlew checkstyleMain checkstyleTest` | yes | Checkstyle 기반 backend 정적 분석 |
| backend-coverage | `./gradlew jacocoTestReport jacocoTestCoverageVerification` | yes | JaCoCo report 생성 및 line coverage 60% 하한 검증 |
| migration-smoke | `./gradlew test --tests com.smartcloset.persistence.SchemaMigrationSmokeTest` | yes | Flyway baseline migration 후 Hibernate validate 검증 |
| mysql-migration-smoke | 아래 `Clean MySQL migration smoke` 절차 | no | 임시 MySQL 8.4 clean DB에서 Flyway migration과 prod Hibernate validate 확인 |
| monitoring-smoke | 아래 `Monitoring smoke` 절차 | no | Actuator health/prometheus endpoint와 dashboard JSON 확인 |
| build | `./gradlew build` | yes | Spring Boot 애플리케이션 빌드 |
| harness-test | `python3 -m pytest scripts/tests` | yes | Harness 운영 스크립트 회귀 테스트 |
| docs-check | `python3 scripts/checks.py --docs-check --include-final-docs` | yes | phase 최종 문서 계약과 MVP 제외 범위 검증 |
| compose-config | `docker compose config --quiet` | no | Docker Compose 파일 구문과 서비스 구성 확인 |
| compose-up | `test -f .env || cp .env.example .env; docker compose up --build` | yes | Docker Compose로 MySQL, 백엔드, 프론트엔드 실행 |
| compose-down | `docker compose down` | yes | Docker Compose 중지 |
| compose-reset | `docker compose down -v` | yes | Docker Compose 중지 및 DB/image volume 초기화 |
| prod-compose-smoke | `scripts/prod-compose-smoke.sh` | no | prod compose, app health/API, disabled OpenAPI, Nginx frontend 확인 |
| docker-build | `docker build -t smartcloset-app:local .` | no | app image build와 Dockerfile hardening 확인 |
| frontend-prod-build | `docker build -f frontend/Dockerfile --build-arg VITE_API_BASE_URL=http://localhost:8080 -t smartcloset-frontend:prod-smoke frontend` | no | Vite build 산출물을 Nginx static image로 빌드 |
| docker-image-smoke | 아래 `Docker image hardening smoke` 절차 | no | non-root user, JVM env, Dockerfile healthcheck 확인 |
| quality-gate | 아래 `CI quality gate smoke` 절차 | yes | backend static analysis/coverage와 frontend lint/test 확인 |
| security-scan | 아래 `CI security scan smoke` 절차 | no | Trivy dependency/image high/critical 취약점 게이트 확인 |
| compose-volume-permission-smoke | 아래 `Compose image volume permission smoke` 절차 | no | 기존 이미지 volume 소유권을 app UID/GID로 보정하는지 확인 |
| mysql-backup | `scripts/mysql-backup.sh` | no | 실행 중인 Compose MySQL container에서 backup dump 생성 |
| mysql-restore | `SMARTCLOSET_RESTORE_CONFIRM=restore scripts/mysql-restore.sh <backup.sql>` | no | 명시 확인 후 기존 MySQL DB에 backup dump replay |
| mysql-backup-restore-smoke | 아래 `MySQL backup/restore smoke` 절차 | no | 임시 MySQL container에서 backup/restore script 검증 |
| review | `python3 scripts/doctor.py --instance` | no | 템플릿과 프로젝트 운영 상태 점검 |
| autopilot-test | `python3 -m pytest scripts/tests/test_autopilot.py` | no | Harness autopilot 스크립트 테스트 |
| phase | `python3 scripts/execute.py <phase-name>` | no | Harness phase 실행 |
| autopilot | `python3 scripts/autopilot.py 10-smartcloset-ai-clothing-assist --base main --max-review-fixes 2 --unsafe` | no | MVP10 step별 PR 생성, 자체 리뷰, 이슈 기록, 자동 병합 루프 |

Harness Codex 호출은 전역 Codex 설정을 그대로 상속하지 않고 reasoning effort를 명시한다. `execute.py`의 step 구현 기본값은 `medium`이고, `autopilot.py`의 기본값은 step 구현 `medium`, PR self-review `high`, 자동 fix `medium`이다. `xhigh`는 `--allow-xhigh`와 함께 명시한 경우에만 허용한다.

autopilot 운영 옵션과 안전장치:

- `--base`를 생략하면 origin HEAD 브랜치를 자동 감지한다(실패 시 `main`).
- `--dry-run`은 실행 없이 pending step과 브랜치 계획만 출력한다.
- `--max-steps N`은 한 번의 실행에서 최대 N개의 step PR만 병합하고 멈춘다.
- 동시 실행은 `.codex/autopilot.lock`으로 차단한다(stale lock은 자동 회수).
- step 문서의 인수 기준 명령은 실행 전에 `guard.py` 위험 명령 정책을 통과해야 하고, `execute.py`도 codex의 completed 보고 후 인수 기준을 직접 재실행해 검증한다.
- PR은 `gh pr checks --watch`로 원격 체크 통과를 확인한 뒤에만 squash merge한다. "no checks reported"는 ready 직후 체크 런 생성 전 레이스일 수 있어 60초 grace 동안 재확인하고, 그래도 없으면 체크 없는 저장소로 판단해 진행한다. CI가 없는 저장소는 `--allow-no-checks`로 grace 대기를 생략한다.
- 금지 범위 규칙은 전부 데이터로 관리한다: 전역 규칙은 `.codex/scope-rules.json`(`forbidden`), phase별 확장/허용은 `phases/<phase>/scope-rules.json`(`extraForbidden`, `allowedScopeMessages` — `steps`/`stepNames`/`requiresAnyLowered`/`forbidsAnyLowered`). 스캐너 코드에는 키워드가 없으므로 autopilot.py 자체는 스캔 대상이고, scope-rules.json 파일만 이름 기준으로 제외된다.
- stop 훅의 기본 검사는 lint만 실행한다. test/build까지 돌리려면 `.codex/project-profile.json`의 `stageChecks.stop`으로 확장한다.
- `execute.py`는 phase README/step 문서가 참조하는 `docs/*.md`만 step 프롬프트에 첨부한다(참조가 없으면 전체 첨부, `guardrailDocs` 프로필 키가 있으면 그 목록이 우선).

## 문서 전환 검증 명령

```bash
git diff --check
python3 scripts/checks.py --docs-check-config phases/10-smartcloset-ai-clothing-assist/docs-checks.json --docs-check
```

## P0 공유 검증 명령

```bash
./gradlew test
./gradlew checkstyleMain checkstyleTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
./gradlew test --tests com.smartcloset.persistence.SchemaMigrationSmokeTest
./gradlew build
(cd frontend && npm audit --audit-level=high)
(cd frontend && npm run lint)
(cd frontend && npm test)
(cd frontend && npm run build)
docker compose config --quiet
sh -n scripts/mysql-backup.sh scripts/mysql-restore.sh scripts/prod-compose-smoke.sh
scripts/prod-compose-smoke.sh
```

MVP10 구현 step의 최소 검증:

```bash
./gradlew test
(cd frontend && npm run build)
python3 scripts/checks.py --docs-check-config phases/10-smartcloset-ai-clothing-assist/docs-checks.json --docs-check
```

Docker Compose smoke:

```bash
docker compose down -v
test -f .env || cp .env.example .env
docker compose up --build -d
for i in $(seq 1 60); do
  curl -fsS http://localhost:8080/actuator/health >/dev/null && break
  sleep 2
done
curl -fsS http://localhost:8080/actuator/health >/dev/null
curl -fsS http://localhost:8080/actuator/prometheus | rg 'jvm_info|smartcloset_'
curl -fsS http://localhost:8080/v3/api-docs >/dev/null
curl -fsS http://localhost:5173 >/dev/null
docker compose down
```

Docker Compose 기본 profile은 `.env.example`의 `SPRING_PROFILES_ACTIVE=local`이다. demo user와 최소 옷장 seed는 `local`/`demo` profile에서 `SMARTCLOSET_SEED_ENABLED=true`일 때만 생성된다. 깨끗한 MySQL volume은 Flyway `V*.sql` migration으로 생성하고 Hibernate `ddl-auto=validate`로 검증한다. MVP10 AI 분석은 기본 비활성이며, `CLOTHING_ANALYSIS_ENABLED=false`, `SPRING_AI_MODEL_CHAT=none`, 빈 `OPENAI_API_KEY` 상태에서도 Compose 실행이 가능해야 한다.

`prod` profile은 local placeholder `JWT_SECRET`과 Hibernate `ddl-auto=update`를 fail-fast로 막고, Swagger UI/API docs를 기본 비활성화한다. 운영 DB schema 변경은 Flyway migration으로 추적하며, 기존 운영 DB 편입은 배포 절차에서 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`를 명시한 경우에만 허용한다. AWS adapter, RDS/Secrets Manager 구성은 후속 범위다.

Production Compose smoke:

```bash
scripts/prod-compose-smoke.sh
```

`docker-compose.prod.yml`은 local/demo `docker-compose.yml`과 별도 파일이고 top-level project name은 `smartcloset-prod`다. Prod volume은 기본값 `smartcloset-prod-mysql-data`, `smartcloset-prod-clothing-image-data`로 명시해 local compose volume과 충돌하지 않게 한다. Smoke는 `MYSQL_DATA_VOLUME_NAME`, `CLOTHING_IMAGE_DATA_VOLUME_NAME`을 임시 project prefix로 override해 실제 prod volume을 건드리지 않는다. `.env.prod.example`은 secret 값을 비워 둔 checklist이므로 그대로 기동하지 않고, 운영 환경 또는 smoke 전용 임시 env에서 `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `FRONTEND_AUTH_CALLBACK_URL`, `VITE_API_BASE_URL`을 채워야 한다. Prod compose는 `SPRING_PROFILES_ACTIVE=prod`, `SMARTCLOSET_SEED_ENABLED=false`, `SPRINGDOC_API_DOCS_ENABLED=false`, `SPRINGDOC_SWAGGER_UI_ENABLED=false`, refresh/OAuth state cookie `Secure=true`를 기준으로 한다. Frontend service는 `frontend/Dockerfile`로 Vite build 산출물을 만들고 Nginx non-root static server로 서빙한다. `scripts/prod-compose-smoke.sh`의 project name override는 `SMARTCLOSET_PROD_SMOKE_PROJECT`만 사용하고, 값은 `smartclosetprodsmoke` prefix로 제한한다.

CI security scan smoke:

```bash
./gradlew build
(cd frontend && npm ci && npm audit --audit-level=high && npm run build)
docker build -t smartcloset-app:security-smoke .
docker build -f frontend/Dockerfile --build-arg VITE_API_BASE_URL=http://localhost:8080 -t smartcloset-frontend:security-smoke frontend

docker run --rm -v "$PWD":/workspace -w /workspace \
  aquasec/trivy:0.71.2 fs --quiet --scanners vuln --vuln-type library \
  --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 frontend

docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD":/workspace \
  aquasec/trivy:0.71.2 image --quiet --scanners vuln --vuln-type os,library \
  --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 smartcloset-app:security-smoke

docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD":/workspace \
  aquasec/trivy:0.71.2 image --quiet --scanners vuln --vuln-type os,library \
  --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 smartcloset-frontend:security-smoke
```

CI security gate는 fix 가능한 `HIGH`/`CRITICAL` 취약점을 실패 처리한다. Frontend dependency는 `npm audit`과 Trivy filesystem scan으로 차단하고, backend runtime library 취약점은 app image scan의 `app.jar` 분석으로 확인한다. `ignore-unfixed`로 인해 현재 fix가 없는 항목은 차단하지 않지만, 예외를 suppression으로 둘 때는 ADR이나 보안 문서에 취약점 ID, 영향 범위, 만료일, 보완 통제를 기록한다.

Spring Boot 4.0.6 기준에서 추가 보안 패치가 필요한 runtime library는 `build.gradle`의 dependency resolution 규칙으로 버전을 지정한다. 버전 조정 시 전체 backend test, coverage, build와 CI app image scan을 함께 검증한다.

CI quality gate smoke:

```bash
./gradlew checkstyleMain checkstyleTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
(cd frontend && npm ci && npm run lint && npm test && npm run build)
```

JaCoCo는 `build/reports/jacoco/test/html/index.html`과 `build/reports/jacoco/test/jacocoTestReport.xml`을 생성한다. 현재 line coverage 하한은 60%이며, 하한 미달 시 `jacocoTestCoverageVerification`과 Gradle `check`가 실패한다. Frontend lint는 ESLint flat config, frontend test는 Vitest를 기준으로 한다.

Clean MySQL migration smoke는 프로젝트 Docker Compose volume을 사용하지 않고 임시 MySQL container로 확인한다.

```bash
docker run --rm --name smartcloset-mysql-migration-smoke \
  -e MYSQL_DATABASE=smartcloset \
  -e MYSQL_USER=smartcloset \
  -e MYSQL_PASSWORD=smartcloset \
  -e MYSQL_ROOT_PASSWORD=root \
  -p 33307:3306 \
  -d mysql:8.4

for i in $(seq 1 60); do
  docker exec smartcloset-mysql-migration-smoke \
    mysqladmin ping -h 127.0.0.1 -usmartcloset -psmartcloset --silent && break
  sleep 2
done

SPRING_PROFILES_ACTIVE=prod \
JWT_SECRET=prod-secret-value \
SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:33307/smartcloset?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8' \
SPRING_DATASOURCE_USERNAME=smartcloset \
SPRING_DATASOURCE_PASSWORD=smartcloset \
SPRING_JPA_HIBERNATE_DDL_AUTO=validate \
SPRING_FLYWAY_ENABLED=true \
SPRING_FLYWAY_BASELINE_ON_MIGRATE=false \
SMARTCLOSET_SEED_ENABLED=false \
SPRING_AI_MODEL_CHAT=none \
OPENAI_API_KEY= \
./gradlew bootRun --args='--server.port=0'

docker stop smartcloset-mysql-migration-smoke
```

`Started SmartClosetApplication` 로그가 보이면 Flyway V1 적용과 Hibernate validate가 통과한 것이다. 확인 후 `Ctrl-C`로 app을 종료하고 마지막 `docker stop`을 실행한다.

Docker image hardening smoke:

```bash
docker build -t smartcloset-app:hardening-smoke .
test "$(docker inspect -f '{{.Config.User}}' smartcloset-app:hardening-smoke)" = "10001:10001"
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' smartcloset-app:hardening-smoke | rg '^JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0$'
docker inspect -f '{{json .Config.Healthcheck.Test}}' smartcloset-app:hardening-smoke | rg 'SMARTCLOSET_HEALTHCHECK_URL|curl'
test "$(docker run --rm --entrypoint id smartcloset-app:hardening-smoke -u)" = "10001"
docker image rm smartcloset-app:hardening-smoke
```

Compose image volume permission smoke:

```bash
docker volume create smartclosetpermtest_clothing-image-data
docker run --rm \
  -v smartclosetpermtest_clothing-image-data:/data/smartcloset/clothing-images \
  busybox:1.36 \
  sh -c "mkdir -p /data/smartcloset/clothing-images && chown -R 0:0 /data/smartcloset/clothing-images"
docker compose -p smartclosetpermtest run --rm --no-deps clothing-image-volume-permissions
test "$(docker run --rm \
  -v smartclosetpermtest_clothing-image-data:/data/smartcloset/clothing-images \
  busybox:1.36 \
  stat -c '%u:%g' /data/smartcloset/clothing-images)" = "10001:10001"
docker compose -p smartclosetpermtest down -v
docker volume rm smartclosetpermtest_clothing-image-data
```

MySQL backup/restore smoke:

```bash
docker run --rm --name smartcloset-mysql-backup-smoke \
  -e MYSQL_DATABASE=smartcloset \
  -e MYSQL_USER=smartcloset \
  -e MYSQL_PASSWORD=smartcloset \
  -e MYSQL_ROOT_PASSWORD=root \
  -p 33309:3306 \
  -d mysql:8.4

for i in $(seq 1 60); do
  docker exec smartcloset-mysql-backup-smoke \
    mysqladmin ping -h 127.0.0.1 -usmartcloset -psmartcloset --silent && break
  sleep 2
done

docker exec smartcloset-mysql-backup-smoke \
  mysql -usmartcloset -psmartcloset smartcloset \
  -e "CREATE TABLE backup_smoke (id BIGINT PRIMARY KEY, value VARCHAR(20)); INSERT INTO backup_smoke VALUES (1, 'ok');"

MYSQL_CONTAINER_NAME=smartcloset-mysql-backup-smoke \
  scripts/mysql-backup.sh /tmp/smartcloset-backup-smoke.sql

docker exec smartcloset-mysql-backup-smoke \
  mysql -usmartcloset -psmartcloset smartcloset \
  -e "RENAME TABLE backup_smoke TO backup_smoke_before_restore;"

SMARTCLOSET_RESTORE_CONFIRM=restore \
MYSQL_CONTAINER_NAME=smartcloset-mysql-backup-smoke \
  scripts/mysql-restore.sh /tmp/smartcloset-backup-smoke.sql

docker exec smartcloset-mysql-backup-smoke \
  mysql -N -usmartcloset -psmartcloset smartcloset \
  -e "SELECT value FROM backup_smoke WHERE id = 1;" | rg '^ok$'

rm -f /tmp/smartcloset-backup-smoke.sql
docker stop smartcloset-mysql-backup-smoke
```

Restore는 대상 DB를 비우는 full replacement가 아니라 기존 DB에 dump 내용을 replay하는 작업이므로 `SMARTCLOSET_RESTORE_CONFIRM=restore`를 명시해야 실행된다. 위 smoke에서 `backup_smoke_before_restore` 같은 extra object가 남을 수 있는 것은 의도된 replay semantics다. 운영 DB restore는 별도 점검 창, 최신 backup 확인, 애플리케이션 write traffic 중지, 필요한 경우 clean DB/volume 준비, restore 후 migration/validate 확인을 거친다.

Monitoring smoke:

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/actuator/prometheus | rg 'jvm_info|hikaricp_connections|smartcloset_'
python3 -m json.tool monitoring/grafana/smartcloset-dashboard.json >/dev/null
```

추천, 날씨, AI 옷 분석 요청을 한 번 이상 실행한 뒤에는 `/actuator/prometheus`에서 `smartcloset_recommendation_*`, `smartcloset_weather_provider_*`, `smartcloset_clothing_analysis_*` metric을 확인할 수 있다. KMA 장애 중 마지막 정상 KMA 값으로 응답하면 `smartcloset_weather_provider_requests_total{outcome="stale_cache_fallback"}`로 구분된다. Prometheus scrape와 alert rule baseline은 `monitoring/prometheus/`, Alertmanager local null receiver는 `monitoring/alertmanager/`, Grafana import용 dashboard는 `monitoring/grafana/` 아래에 둔다. Local Prometheus가 Alertmanager를 함께 사용할 때는 `host.docker.internal:9093`으로 접근 가능한 Alertmanager를 실행한다.

Structured logging/tracing smoke:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
curl -i http://localhost:8080/actuator/health | rg 'X-Trace-Id|HTTP/'
```

Console log는 기본적으로 ECS JSON 형식이다. 사람이 읽기 쉬운 local log가 필요하면 `LOGGING_STRUCTURED_FORMAT_CONSOLE=`로 override한다. OTLP trace export를 확인할 때는 collector를 준비한 뒤 `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=true`와 `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4318/v1/traces`를 설정한다. 기본 local/demo 실행은 OTLP log/metrics push export도 비활성이다. API/auth/provider 실패 로그에는 `code`, `status`, `method`, `path`, `exception`, `error_message` field만 남기며 request body, Authorization header, cookie, query string, raw exception message는 남기지 않는다.

Local email outbox smoke:

```bash
docker compose exec app tail -n 20 /tmp/smartcloset-email-outbox.log
```

이 outbox는 local/demo 이메일 인증과 비밀번호 재설정 token 확인용이다. Action token 원문은 SLF4J application log에 남기지 않는다.

MVP10 최종 QA에서는 Codex Browser를 우선 사용하고, 필요하면 Chrome 또는 Computer Use로 대체해 옷장 AI 후보 체크, Auth, 추천, 내 취향, 위치, 기록, 계정 설정 화면을 데스크톱 1440px과 모바일 390px 기준으로 확인한다. 결과는 `docs/qa/mvp10-ai-clothing-assist-qa.md`에 기록한다. Final docs-check는 아래 행이 없으면 실패한다.

- `desktop 1440px | 옷장 AI 후보 체크 | PASS`
- `mobile 390px | 옷장 AI 후보 체크 | PASS`
- `backend API | analysis cases | PASS`
- `recommendation | AI 분석 전후 추천 불변 | PASS`

MVP8 세션 복구, 이메일 인증, 비밀번호 재설정, Google provider 상태, 계정 삭제, 기존 위치/날씨 추천과 이미지/피드백 흐름 유지 여부도 함께 확인한다.

## URLs

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Actuator health: `http://localhost:8080/actuator/health`
- Prometheus metrics: `http://localhost:8080/actuator/prometheus`
- Frontend: `http://localhost:5173`

## 문서 검증

MVP 범위나 baseline을 바꿀 때 전체 확인 대상은 `docs/MVP_CHANGE_CHECKLIST.md`를 먼저 본다.
검증 규칙은 `phases/{phase}/docs-checks.json`에서 관리한다. MVP 범위가 바뀌면 `scripts/checks.py`가 아니라 해당 phase의 rule 파일을 갱신한다.
`harness-test`와 `docs-check`는 step-local `manual` 검증에서는 제외되고, 마지막 step 이후 `final` stage 또는 직접 실행에서만 수행한다.
프로젝트 skill의 Documentation Sync Rules는 에이전트가 따라야 하는 정성 규칙이고, `docs-checks.json`은 final stage에서 자동으로 잡을 수 있는 핵심 회귀 신호만 담는다.

```bash
python3 scripts/checks.py --stage final
python3 -m pytest scripts/tests
python3 scripts/checks.py --docs-check-config phases/10-smartcloset-ai-clothing-assist/docs-checks.json --docs-check
```

## 자동 PR 루프

아래 명령은 clean worktree, 유효한 `gh auth status`, `origin` 원격, 최신 `main` 브랜치를 전제로 한다.

```bash
python3 scripts/autopilot.py 10-smartcloset-ai-clothing-assist --base main --max-review-fixes 2 --unsafe
```

자동 루프는 다음 pending step만 `codex/{phase}-step{N}-{name}` 브랜치에서 실행하고 Draft PR을 생성한다. 자체 리뷰 gate는 해당 step 문서의 `## 인수 기준` fenced command와 `git diff --check`를 실행한다. `execute.py --step` 또는 `--next-step-only`가 마지막 pending step을 완료하면 phase 완료 metadata를 기록하기 전에 `python3 scripts/checks.py --stage final`을 실행하므로 마지막 step PR은 merge 전에 final gate를 통과해야 한다. 로컬 검증과 자체 리뷰가 통과하면 PR을 ready로 전환한 뒤 squash merge하고 다음 step으로 진행한다. 실패하면 같은 PR에 자체 리뷰 코멘트, GitHub Issue, `issues/{phase}/issue-N.md`를 남긴 뒤 같은 브랜치에서 최대 2회 자동 수정과 재리뷰를 진행한다.
