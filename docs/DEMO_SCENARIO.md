# Demo Scenario: SmartCloset MVP10

## 데모 목표

Docker Compose로 SmartCloset 백엔드, MySQL, React 프론트엔드를 실행한 뒤 MVP8 계정 안정성, MVP9 화면 구조, MVP10 AI 옷 등록 보조 흐름이 함께 유지되는지 확인한다.

MVP10 데모의 핵심은 옷 사진을 선택한 뒤 사용자가 직접 `AI 후보 체크`를 실행하고, confidence가 낮은 필드를 확인/수정한 뒤 기존 저장 흐름으로 옷을 등록하는 것이다.

기본 공유 데모는 AI 분석 비활성 상태로도 성공해야 한다. 실제 OpenAI 호출 데모는 환경변수를 명시적으로 설정한 경우에만 진행한다.

## MVP10 데모 범위

포함:

- Auth 화면 visual/form layout 확인
- 회원가입 후 이메일 인증 필요 상태 확인
- refresh cookie 기반 새로고침 세션 복구
- Google provider enabled/disabled 상태 확인
- 추천 dashboard와 규칙 기반 추천 결과 확인
- 옷장 목록, 옷 이미지, 옷 추가/수정 form 확인
- 옷 등록/수정 form의 AI 후보 체크 버튼, loading/error/review state 확인
- confidence 낮은 필드의 흐림/확인 필요 표시 확인
- 사용자가 수정/확인한 값만 기존 옷 저장 API로 저장되는지 확인
- 내 취향 swatch/chip/toggle 입력 확인
- 위치 검색과 현재 위치 후보 찾기 확인
- 기록 calendar/timeline과 위치/날씨 snapshot 확인
- profile pill/menu 기반 계정 설정 진입
- 계정 삭제와 데이터 삭제
- 데스크톱 1440px, 모바일 390px 반응형 확인

제외:

- AWS 배포
- S3 storage
- SES/SMTP 실제 발송
- Secrets Manager
- CD 자동화
- Redis
- DB schema 변경
- 추천 규칙 변경
- AI/GPT 옷차림 추천
- AI-generated 추천 이유
- 사용자 확인 없는 자동 저장
- 다중 이미지, 이미지 편집, EXIF 분석, image moderation

## 데모 전제

- `.env`는 `.env.example`을 복사해 만든다.
- 이메일 발송은 local outbox 기반 `ConsoleEmailSender` 기준이며 실제 메일은 발송하지 않는다.
- Google OAuth 설정이 없으면 provider disabled 상태로 데모한다.
- KMA key가 없어도 `WEATHER_FALLBACK_ENABLED=true`이면 fallback weather로 추천 데모 가능하다.
- `CLOTHING_ANALYSIS_ENABLED=false`, `SPRING_AI_MODEL_CHAT=none`, 빈 `OPENAI_API_KEY` 상태에서도 앱이 정상 실행되어야 한다.
- 실제 AI 분석 데모를 할 때만 `CLOTHING_ANALYSIS_ENABLED=true`, `SPRING_AI_MODEL_CHAT=openai`, `OPENAI_API_KEY`를 설정한다.
- Docker Compose reset 시 DB와 이미지 volume이 초기화된다.

## 실행

```bash
docker compose down -v
test -f .env || cp .env.example .env
docker compose up --build
```

Frontend:

```text
http://localhost:5173
```

Swagger UI:

```text
http://localhost:8080/swagger-ui/index.html
```

## React 앱 MVP10 데모 시나리오

### 1. Auth 화면과 회원가입

1. Frontend에 접속한다.
2. Auth 화면의 visual background와 중앙 form이 데스크톱/모바일에서 읽히는지 확인한다.
3. 새 이메일/password/name으로 회원가입한다.
4. 회원가입 성공 후 이메일 인증 필요 안내를 확인한다.

기대 결과:

- Auth form CTA와 입력값이 390px 모바일에서 잘리지 않는다.
- 가입 직후 자동 로그인되지 않는다.
- access token이 JSON 응답에 포함되지 않는다.
- 인증 안내와 재요청 진입점이 표시된다.

### 2. 이메일 인증과 로그인

1. backend local outbox 파일에서 인증 token을 확인한다. Docker Compose에서는 `docker compose exec app tail -n 20 /tmp/smartcloset-email-outbox.log`를 사용한다.
2. 앱의 이메일 인증 확인 화면에 token을 입력하거나 링크를 연다.
3. 인증된 이메일/password로 로그인한다.
4. 앱을 새로고침한다.

기대 결과:

- 이메일 인증이 성공한다.
- login 응답은 access token을 반환한다.
- refresh token 값은 JSON body에 없다.
- 새로고침 후 `POST /api/auth/refresh`로 세션이 복구된다.
- 비밀번호, access token, refresh token은 브라우저 저장소에 저장되지 않는다.

### 3. Navigation과 account entry

1. 로그인 후 `추천` 화면이 기본 view인지 확인한다.
2. 데스크톱에서 상단 탭 `추천`, `옷장`, `내 취향`, `위치`, `기록`을 확인한다.
3. 모바일에서 하단 탭 `추천`, `옷장`, `내 취향`, `위치`, `기록`을 확인한다.
4. 우측 상단 profile pill/menu에서 계정 설정으로 진입한다.

기대 결과:

- primary nav에 계정 탭이 없다.
- 계정 설정은 profile에서 열린다.
- 상단 탭과 하단 탭의 텍스트가 잘리지 않는다.

### 4. 옷장 AI 후보 체크

1. `옷장`에서 새 옷 추가 화면을 연다.
2. jpg/png/webp 옷 사진을 선택하고 preview를 확인한다.
3. `AI 후보 체크` 버튼을 누른다.
4. 분석 성공 시 후보값이 이름, 카테고리, 색상, 소재, 기온 범위, 비 적합성, style tag form에 적용되는지 확인한다.
5. `확인 필요`로 표시된 field를 수정하거나 확인 처리한다.
6. 확인 필요 field를 일부 남긴 채 저장을 시도해 저장 전 confirmation을 확인한다.
7. 최종 저장 후 옷 목록과 상세에서 값과 이미지가 보이는지 확인한다.

기대 결과:

- 이미지 선택만으로 자동 분석 호출이 실행되지 않는다.
- 분석은 `POST /api/clothes/analyze-image` 보호 API를 사용한다.
- 분석 이미지는 저장되지 않는다.
- confidence가 낮은 field는 흐림/확인 필요 상태로 보인다.
- 사용자가 확인/수정한 최종 값만 `POST /api/clothes` 또는 `PUT /api/clothes/{clothingId}`로 저장된다.
- 이미지 저장은 기존 `PUT /api/clothes/{clothingId}/image`를 사용한다.
- 분석 실패 또는 기능 비활성 상태에서도 manual form 입력과 저장이 가능하다.
- 390px 모바일에서 preview, 버튼, badge, control이 겹치지 않는다.

### 5. 추천 dashboard

1. `추천` 화면에서 날씨, 위치, 상황, 예보 시간대, 준비 상태를 확인한다.
2. 상황과 예보 시간대를 선택해 추천을 생성한다.
3. 추천 결과의 옷 이미지, 추천 이유, 점수 상세를 확인한다.
4. 착용 완료와 피드백 저장/clear를 수행한다.

기대 결과:

- 추천 생성은 `POST /api/recommendations`를 사용한다.
- 추천은 규칙 기반으로 생성된다.
- AI 분석 결과, confidence, reviewRequiredFields는 추천 결과에 표시되거나 저장되지 않는다.
- 옷 조합과 "오늘 입기 좋은 이유"가 점수표보다 먼저 읽힌다.
- 이미지가 없는 옷은 fallback visual로 식별 가능하다.

### 6. 내 취향, 위치, 기록

1. `내 취향`에서 색상 swatch, 소재 toggle, style tag chip을 수정한다.
2. `위치`에서 `일산동` 검색과 현재 위치 후보 찾기를 확인한다.
3. 후보를 선택해 계정 위치를 저장한다.
4. `기록`에서 과거 추천의 위치/날씨 요약이 현재 위치 변경과 독립적으로 유지되는지 확인한다.

기대 결과:

- swatch/chip/toggle control이 모바일에서 겹치지 않는다.
- 위치 검색은 내부 KMA catalog 기준이다.
- 브라우저 GPS 원문 좌표를 DB에 저장하지 않는다.
- 선택된 동네만 사용자 위치로 저장된다.
- 과거 추천 snapshot은 현재 위치 변경과 독립적으로 유지된다.

### 7. 계정 설정과 계정 삭제

1. Profile pill/menu에서 계정 설정으로 이동한다.
2. 이메일 인증 상태, 로그인 제공자, 세션 상태를 확인한다.
3. 계정 정보 카드 오른쪽 아래의 작은 `계정 삭제` 버튼으로 삭제 팝업을 연다.
4. confirmation `DELETE`를 입력한다.
5. Password login enabled 계정이면 현재 비밀번호를 입력한다.
6. 삭제를 실행한다.

기대 결과:

- 계정 삭제는 작은 팝업으로 다른 설정과 분리되어 보인다.
- 계정 삭제가 성공한다.
- 로그인 상태가 초기화된다.
- 기존 refresh cookie는 서버 응답으로 만료되어 더 이상 사용할 수 없다.
- 삭제된 계정의 옷장, 추천 이력, 이미지 파일이 남아 보호 API에서 조회되지 않는다.

## Backend API 실패 케이스 확인

- AI 분석 기능 비활성: `503 CLOTHING_ANALYSIS_DISABLED`
- AI 분석 provider 장애 또는 timeout: `503 CLOTHING_ANALYSIS_UNAVAILABLE`
- AI 분석 일일 제한 초과: `429 CLOTHING_ANALYSIS_LIMIT_EXCEEDED`
- 잘못된 이미지 형식, 크기 초과, 손상 파일 또는 20,000,000픽셀 초과: 업로드/교체와 분석 모두 `400 INVALID_REQUEST`, `details[].field=image`
- 로그인 실패 반복 제한 초과: `429 LOGIN_ATTEMPT_LIMIT_EXCEEDED`
- 추천 생성 반복 호출 제한 초과: `429 RECOMMENDATION_CREATION_LIMIT_EXCEEDED`
- 미인증 password 계정 login: `403 EMAIL_VERIFICATION_REQUIRED`
- 만료/사용 완료 인증 token confirm: `400 ACCOUNT_TOKEN_INVALID`
- reset token 재사용: `400 ACCOUNT_TOKEN_INVALID`
- refresh cookie 없음: `401 UNAUTHORIZED`
- revoked refresh token 사용: `401 INVALID_TOKEN`
- Google provider 설정 없음: provider status `enabled=false`
- 계정 삭제 confirmation 누락: `400 INVALID_REQUEST`
- 계정 삭제 password 불일치: `401 UNAUTHORIZED`

### 손상 이미지 검증

1. 정상 JPEG/PNG/WebP 사진으로 업로드와 분석이 기존 흐름대로 동작하는지 확인한다. WebP는 lossy/lossless 파일을 각각 사용한다.
2. 파일 signature는 유지하고 본문을 자른 파일을 `PUT /api/clothes/{clothingId}/image`와 `POST /api/clothes/analyze-image`로 전송한다.
3. 두 API가 `400 INVALID_REQUEST`와 `image` 필드 오류를 반환하며, 교체 실패 후 기존 이미지와 metadata가 유지되는지 확인한다.
4. 분석 기능이 활성화된 테스트 환경에서 손상 파일을 보낸 뒤 정상 사진을 분석해 손상 파일이 일일 한도를 소비하지 않았는지 확인한다. provider 미호출 여부는 서비스 회귀 테스트로 검증한다.

## 완료 기준

- MVP10 AI 옷 등록 보조 흐름이 데스크톱 1440px과 모바일 390px에서 겹침/잘림 없이 동작한다.
- AI 분석은 수동 버튼으로만 실행된다.
- 기능 비활성 또는 API key 없음 상태에서도 기존 local demo가 동작한다.
- Auth, 추천, 옷장, 내 취향, 위치, 기록, 계정 설정 화면이 데스크톱/모바일에서 유지된다.
- MVP8 세션 복구, 이메일 인증, 비밀번호 재설정, Google provider 상태, 계정 삭제 UX가 유지된다.
- 백엔드 인증/API envelope, DB schema, 추천 규칙이 변경되지 않는다.
- Docker Compose local 환경에서 앱이 정상 실행된다.
