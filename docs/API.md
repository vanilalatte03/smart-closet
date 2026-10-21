# API: SmartCloset MVP10 Contract

이 문서는 SmartCloset MVP10 API 계약을 설명한다. MVP10은 사진 기반 AI 옷 등록 보조를 추가하지만, 기존 옷 저장 JSON API, 옷 이미지 저장 API, 추천 API, 인증 API 계약을 대체하지 않는다.

MVP10에서 새로 추가하는 API는 `POST /api/clothes/analyze-image` 하나다. 이 API는 인증 사용자의 multipart image를 분석해 옷 등록 후보와 confidence를 반환하며, 이미지를 저장하거나 추천 결과를 생성하지 않는다.

## MVP10 API 결정

- 공개 `userId` query parameter를 추가하지 않는다.
- 현재 사용자 전용 response DTO에 `userId`를 노출하지 않는다.
- Access token은 JWT bearer token으로 유지한다.
- Refresh token은 HttpOnly cookie로만 전달하고 JSON 응답에 포함하지 않는다.
- Refresh token 원문은 DB, JSON 응답, 로그에 저장하거나 노출하지 않는다. 이메일 인증 token 원문과 비밀번호 재설정 token 원문은 DB, JSON 응답, 로그에 저장하거나 노출하지 않고 local/demo `SMARTCLOSET_EMAIL_OUTBOX_PATH` outbox 파일로만 확인한다.
- Password signup 직후 access token을 발급하지 않는다.
- 미인증 password 계정 login은 실패한다.
- 추천 생성은 계속 `POST /api/recommendations`다.
- 추천 생성은 user별 process-local 반복 호출 제한을 적용하며 초과 시 `RECOMMENDATION_CREATION_LIMIT_EXCEEDED`로 실패한다.
- 옷 등록/수정은 계속 JSON `POST /api/clothes`, `PUT /api/clothes/{clothingId}`다.
- 옷 이미지 업로드/교체는 계속 별도 multipart `PUT /api/clothes/{clothingId}/image`다.
- AI 분석은 저장 전 후보 제안만 반환한다.
- AI 분석 결과는 DB, 추천 이력, 옷 이미지 storage에 저장하지 않는다.
- AI 분석 결과는 추천 점수, 후보 필터링, tie-break, 추천 이유에 사용하지 않는다.

## 1. 공통 규칙

- 공개 API는 access token 없이 호출 가능하다.
- 보호 API는 `Authorization: Bearer {accessToken}` header가 필요하다.
- Refresh API와 logout은 refresh cookie를 사용할 수 있다.
- JSON API 요청과 응답의 `Content-Type`은 `application/json`이다.
- 이미지 업로드와 이미지 분석 요청은 `multipart/form-data`다.
- 이미지 bytes 조회 응답은 이미지 MIME type을 `Content-Type`으로 반환한다.
- 날짜/시간은 ISO-8601 문자열로 표현한다.
- enum 값은 대문자 문자열로 주고받는다.
- JSON 성공 응답은 항상 `data` 필드를 가진다.
- JSON 실패 응답은 항상 `code`, `message`, `details` 필드를 가진다.
- `details`는 항상 배열이다.
- 실패 응답을 만든 서버 로그는 `code`, `status`, `method`, `path`, exception class, 고정된 error message를 남긴다.
- 서버 로그에는 request body, Authorization header, cookie, query string, raw exception message, token/action token/API key/password를 남기지 않는다.

### 공통 성공 응답

```json
{
  "data": {
    "id": 1
  }
}
```

### 공통 실패 응답

```json
{
  "code": "METHOD_ARGUMENT_NOT_VALID",
  "message": "요청 본문 검증에 실패했습니다.",
  "details": [
    {
      "field": "email",
      "message": "올바른 이메일 형식이어야 합니다."
    }
  ]
}
```

## 2. API 목록

### 공개 API

| Method | Path | Description | Success |
| --- | --- | --- | --- |
| `POST` | `/api/auth/signup` | 회원가입과 이메일 인증 요청 생성 | `201 Created` |
| `POST` | `/api/auth/login` | 로그인, access token 발급, refresh cookie 설정 | `200 OK` |
| `POST` | `/api/auth/refresh` | refresh cookie 회전과 access token 재발급 | `200 OK` |
| `POST` | `/api/auth/logout` | refresh session revoke와 cookie 만료 | `200 OK` |
| `POST` | `/api/auth/email-verification/request` | 이메일 인증 재요청 | `200 OK` |
| `POST` | `/api/auth/email-verification/confirm` | 이메일 인증 확인 | `200 OK` |
| `POST` | `/api/auth/password-reset/request` | 비밀번호 재설정 요청 | `200 OK` |
| `POST` | `/api/auth/password-reset/confirm` | 비밀번호 재설정 확인 | `200 OK` |
| `GET` | `/api/auth/oauth2/providers` | OAuth provider 활성 상태 조회 | `200 OK` |
| `GET` | `/api/auth/oauth2/google` | Google OAuth login 시작 | `302 Found` |
| `GET` | `/api/auth/oauth2/callback/google` | Google OAuth callback | `302 Found` |

### 보호 API

| Method | Path | Description | Success |
| --- | --- | --- | --- |
| `GET` | `/api/users/me` | 현재 사용자 조회 | `200 OK` |
| `PATCH` | `/api/users/me` | 현재 사용자 이름 수정 | `200 OK` |
| `DELETE` | `/api/users/me` | 현재 사용자 계정과 데이터 삭제 | `200 OK` |
| `GET` | `/api/locations?keyword={keyword}` | KMA 행정구역 catalog 검색 | `200 OK` |
| `POST` | `/api/locations/resolve` | 브라우저 좌표를 KMA grid와 위치 후보로 변환 | `200 OK` |
| `GET` | `/api/users/me/location` | 현재 사용자 위치 조회 | `200 OK` |
| `PUT` | `/api/users/me/location` | 현재 사용자 위치 선택 | `200 OK` |
| `GET` | `/api/users/me/preferences` | 현재 사용자 선호도 조회 | `200 OK` |
| `PUT` | `/api/users/me/preferences` | 현재 사용자 선호도 저장 | `200 OK` |
| `GET` | `/api/weather/current` | 현재 사용자 위치 기준 날씨 요약과 source 조회 | `200 OK` |
| `POST` | `/api/clothes/analyze-image` | 옷 사진 기반 등록 후보 분석 | `200 OK` |
| `POST` | `/api/clothes` | 옷 등록 | `201 Created` |
| `GET` | `/api/clothes` | 옷 목록 조회 | `200 OK` |
| `GET` | `/api/clothes/archived` | 보관한 옷 목록 조회 | `200 OK` |
| `GET` | `/api/clothes/{clothingId}` | 옷 상세 조회 | `200 OK` |
| `PUT` | `/api/clothes/{clothingId}` | 옷 전체 수정 | `200 OK` |
| `PATCH` | `/api/clothes/{clothingId}/archive` | 옷 보관 처리 | `200 OK` |
| `PATCH` | `/api/clothes/{clothingId}/unarchive` | 보관한 옷 다시 꺼내기 | `200 OK` |
| `PUT` | `/api/clothes/{clothingId}/image` | 옷 이미지 업로드 또는 교체 | `200 OK` |
| `GET` | `/api/clothes/{clothingId}/image` | 옷 이미지 bytes 조회 | `200 OK` |
| `DELETE` | `/api/clothes/{clothingId}/image` | 옷 이미지 삭제 | `200 OK` |
| `POST` | `/api/recommendations` | 상황/예보 시간대 기반 추천 생성 및 저장 | `201 Created` |
| `GET` | `/api/recommendations?limit={limit}` | 추천 이력 조회 | `200 OK` |
| `PATCH` | `/api/recommendations/{recommendationId}/worn` | 추천 결과 착용 완료 처리 | `200 OK` |
| `PUT` | `/api/recommendations/{recommendationId}/feedback` | 추천 피드백 전체 교체 또는 clear | `200 OK` |

## 3. Auth API

### SignupRequest

```json
{
  "email": "demo@example.com",
  "password": "password123!",
  "name": "Demo User"
}
```

회원가입 시 서버는 기본 위치 `SEOUL`, 위치 source `MANUAL_SEARCH`, 빈 선호도로 user를 생성한다. 기본 옷 프리셋은 signup transaction commit 이후 신규 계정 온보딩 경계에서 준비한다. Password signup은 이메일 인증 전에는 access token을 발급하지 않는다.

실패:

- 이미 존재하거나 동시 회원가입 경합에서 선점된 이메일: `409 EMAIL_ALREADY_EXISTS`

### SignupResponse

```json
{
  "data": {
    "email": "demo@example.com",
    "emailVerificationRequired": true,
    "message": "이메일 인증 후 로그인할 수 있습니다."
  }
}
```

### LoginRequest

```json
{
  "email": "demo@example.com",
  "password": "password123!"
}
```

미인증 password 계정은 `EMAIL_VERIFICATION_REQUIRED`로 실패한다.

같은 정규화 email과 client remote address 조합 또는 같은 client remote address에서 짧은 window 안에 로그인 실패가 반복되면 `LOGIN_ATTEMPT_LIMIT_EXCEEDED`로 실패한다. 현재 MVP10 구현은 Redis를 사용하지 않는 process-local in-memory 제한이며, 클라이언트가 보낸 `X-Forwarded-For` 같은 proxy header를 신뢰하지 않는다.

### AuthResponse

```json
{
  "data": {
    "accessToken": "eyJhbGciOi...",
    "tokenType": "Bearer",
    "user": {
      "email": "demo@example.com",
      "name": "Demo User",
      "role": "USER",
      "emailVerified": true,
      "passwordLoginEnabled": true,
      "authProviders": ["PASSWORD"],
      "createdAt": "2026-05-27T10:00:00",
      "updatedAt": "2026-05-27T10:00:00"
    }
  }
}
```

성공한 login, refresh, OAuth callback은 refresh cookie를 설정한다. Refresh token 원문은 JSON body에 넣지 않는다.

### Refresh

`POST /api/auth/refresh`

Request body는 없다. 서버는 refresh cookie를 읽어 session을 검증하고 token rotation을 수행한다.

실패:

- refresh cookie 없음: `401 UNAUTHORIZED`
- 만료, revoke, hash 불일치, 재사용 의심: `401 INVALID_TOKEN`

### Logout

`POST /api/auth/logout`

Request body는 없다. 서버는 refresh cookie가 있으면 관련 session을 revoke하고 cookie를 만료한다. refresh cookie가 없거나 이미 revoke된 session이어도 성공한다.

```json
{
  "data": {
    "loggedOut": true
  }
}
```

## 4. Email Verification API

### EmailVerificationRequest

```json
{
  "email": "demo@example.com"
}
```

응답은 계정 존재 여부를 과도하게 노출하지 않는다. 이미 인증된 계정도 성공 응답을 반환할 수 있다.

```json
{
  "data": {
    "requested": true
  }
}
```

### EmailVerificationConfirmRequest

```json
{
  "token": "verification-token-from-email"
}
```

성공 응답:

```json
{
  "data": {
    "emailVerified": true
  }
}
```

규칙:

- Token 원문은 DB에 저장하지 않는다.
- Token은 hash로 조회한다.
- Token은 만료 시간이 있고 single-use다.
- 만료, 사용 완료, hash 불일치 시 `ACCOUNT_TOKEN_INVALID`로 실패한다.

## 5. Password Reset API

### PasswordResetRequest

```json
{
  "email": "demo@example.com"
}
```

성공 응답은 계정 존재 여부와 무관하게 동일하다.

```json
{
  "data": {
    "requested": true
  }
}
```

### PasswordResetConfirmRequest

```json
{
  "token": "reset-token-from-email",
  "newPassword": "newPassword123!"
}
```

성공 응답:

```json
{
  "data": {
    "passwordReset": true
  }
}
```

규칙:

- 새 password는 기존 signup password validation을 따른다.
- 성공 시 password hash를 BCrypt로 갱신한다.
- 성공 시 해당 사용자 refresh sessions를 revoke한다.
- Password login disabled 계정은 `PASSWORD_LOGIN_DISABLED`로 실패할 수 있다.

## 6. OAuth API

### OAuthProvidersResponse

`GET /api/auth/oauth2/providers`

```json
{
  "data": {
    "google": {
      "enabled": true,
      "loginUrl": "/api/auth/oauth2/google"
    }
  }
}
```

Google client id/secret/redirect 설정이 없으면 `enabled=false`, `loginUrl=null`이다.

### Google OAuth rules

- OAuth login start는 `/api/auth/oauth2/google`이다.
- OAuth callback은 `/api/auth/oauth2/callback/google`이다.
- OAuth login start는 short-lived HttpOnly state cookie를 설정하고 Google authorization URL의 `state`와 매칭한다.
- OAuth callback의 `state`가 state cookie와 일치하지 않으면 refresh cookie를 발급하지 않고 `UNAUTHORIZED`로 실패한다.
- 성공 시 backend는 refresh cookie를 설정하고 frontend callback URL로 redirect한다.
- Access token은 frontend가 callback 후 `POST /api/auth/refresh`로 받아도 된다.
- Google profile의 email은 verified email이어야 한다.
- 기존 같은 email user가 있으면 social account를 link한다.
- 새 Google user는 password login disabled, email verified 상태로 생성한다.
- 같은 Google 계정 또는 같은 email callback이 동시에 들어와 unique 충돌이 발생하면 이미 생성된 user/social account를 재조회해 정상 로그인으로 수렴한다.

## 7. Account API

### CurrentUserResponse

`GET /api/users/me`

```json
{
  "data": {
    "email": "demo@example.com",
    "name": "Demo User",
    "role": "USER",
    "emailVerified": true,
    "passwordLoginEnabled": true,
    "authProviders": ["PASSWORD", "GOOGLE"],
    "createdAt": "2026-05-27T10:00:00",
    "updatedAt": "2026-05-27T10:00:00"
  }
}
```

현재 사용자 전용 response DTO는 `userId`를 노출하지 않는다.

### UpdateCurrentUserRequest

`PATCH /api/users/me`

```json
{
  "name": "Jiho"
}
```

- `name`은 공백일 수 없고 최대 50자다.
- 응답은 `CurrentUserResponse`와 동일하다.
- 현재 사용자 전용 response DTO는 `userId`를 노출하지 않는다.

### AccountDeletionRequest

`DELETE /api/users/me`

Password login enabled 계정:

```json
{
  "confirmation": "DELETE",
  "password": "password123!"
}
```

Google-only 계정:

```json
{
  "confirmation": "DELETE"
}
```

성공 응답:

```json
{
  "data": {
    "deleted": true
  }
}
```

규칙:

- 보호 API다.
- `confirmation`은 정확히 `DELETE`여야 한다.
- Password login enabled 계정은 현재 비밀번호 검증이 필요하다.
- 현재 사용자 소유 데이터만 삭제한다.
- DB 삭제 대상은 user, clothing items, recommendation results/items, wear histories, refresh sessions, account action tokens, social accounts다.
- 이미지 파일은 `ClothingImageStorage`를 통해 삭제한다.
- 삭제 성공 응답은 logout과 같은 refresh cookie 이름/path/domain/SameSite/Secure 설정으로 `Max-Age=0` 만료 `Set-Cookie` header를 내려준다.
- confirmation 오류, 비밀번호 불일치 등 삭제 실패 응답은 refresh cookie 만료 header를 내려주지 않는다.
- 삭제 후 기존 access token은 사용자 조회에서 `USER_NOT_FOUND` 또는 인증 실패 성격의 응답으로 더 이상 보호 resource를 읽을 수 없어야 한다.

## 8. Clothing API

### ClothingRequest

`POST /api/clothes`, `PUT /api/clothes/{clothingId}`

```json
{
  "name": "흰색 셔츠",
  "category": "TOP",
  "color": "WHITE",
  "material": "COTTON",
  "minTemperature": 18,
  "maxTemperature": 28,
  "rainSuitable": false,
  "styleTags": ["미니멀", "단정"]
}
```

규칙:

- `name`은 공백일 수 없고 최대 50자다.
- `category`는 `TOP`, `BOTTOM`, `OUTER` 중 하나다.
- `color`는 현재 `ClothingColor` enum 중 하나다.
- `material`은 현재 `ClothingMaterial` enum 중 하나다.
- `minTemperature <= maxTemperature`여야 한다.
- `styleTags`는 `null`이면 빈 배열로 처리하고 각 항목은 최대 30자다.
- `styleTags` 배열 내부의 `null` 요소는 허용하지 않는다. 등록·수정 모두 HTTP `400`, `METHOD_ARGUMENT_NOT_VALID`를 반환하며 `details`에 `styleTags[1]`처럼 문제가 있는 요소의 인덱스를 포함한 필드 경로를 제공한다.
- 옷 정보 저장은 JSON API다. 이미지 업로드나 AI 분석 multipart API와 합치지 않는다.

### ClothingResponse

```json
{
  "data": {
    "id": 1,
    "name": "흰색 셔츠",
    "category": "TOP",
    "color": "WHITE",
    "material": "COTTON",
    "minTemperature": 18,
    "maxTemperature": 28,
    "rainSuitable": false,
    "styleTags": ["미니멀", "단정"],
    "archived": false,
    "image": null,
    "createdAt": "2026-06-05T12:00:00",
    "updatedAt": "2026-06-05T12:00:00"
  }
}
```

### Clothing image API

- `PUT /api/clothes/{clothingId}/image`는 multipart part `image`를 받는다.
- `GET /api/clothes/{clothingId}/image`는 이미지 bytes를 반환한다.
- `DELETE /api/clothes/{clothingId}/image`는 이미지가 없어도 성공한다.
- 허용 파일은 5MB 이하 jpg/jpeg/png/webp다.
- MIME type은 `image/jpeg`, `image/png`, `image/webp`만 허용한다.
- 원본 파일명은 저장 경로에 사용하지 않는다.

## 9. Clothing Analysis API

### Analyze clothing image

```http
POST /api/clothes/analyze-image
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data
```

Multipart part:

| Name | Required | Description |
| --- | --- | --- |
| `image` | yes | 분석할 옷 사진. 기존 옷 이미지 검증 규칙과 같은 파일 형식을 사용한다. |

성공 응답:

```json
{
  "data": {
    "analyzable": true,
    "suggestion": {
      "name": "흰색 셔츠",
      "category": "TOP",
      "color": "WHITE",
      "material": "COTTON",
      "minTemperature": 18,
      "maxTemperature": 28,
      "rainSuitable": false,
      "styleTags": ["미니멀", "단정"]
    },
    "fieldConfidence": {
      "name": 0.72,
      "category": 0.94,
      "color": 0.91,
      "material": 0.58,
      "minTemperature": 0.5,
      "maxTemperature": 0.5,
      "rainSuitable": 0.62,
      "styleTags": 0.7
    },
    "reviewRequiredFields": [
      "name",
      "material",
      "minTemperature",
      "maxTemperature",
      "rainSuitable",
      "styleTags"
    ],
    "lowConfidenceThreshold": 0.75
  }
}
```

옷으로 보기 어려운 사진:

```json
{
  "data": {
    "analyzable": false,
    "suggestion": null,
    "fieldConfidence": {},
    "reviewRequiredFields": [],
    "lowConfidenceThreshold": 0.75
  }
}
```

DTO 규칙:

- `suggestion`은 저장된 옷이 아니라 기존 `ClothingRequest` field와 같은 후보값이다.
- `fieldConfidence` 값은 0.0 이상 1.0 이하 숫자다.
- `reviewRequiredFields`는 confidence가 `lowConfidenceThreshold`보다 낮거나 모델이 확인 필요로 판단한 field 이름이다.
- `reviewRequiredFields`의 field 이름은 `ClothingRequest` property 이름과 일치한다.
- 분석 API는 idempotent 저장 API가 아니다. 같은 파일을 여러 번 보내면 비용이 발생할 수 있으므로 프론트는 fingerprint cache를 사용할 수 있다.
- 분석 API는 이미지를 DB나 파일 저장소에 저장하지 않는다.
- 분석 결과는 추천 결과, 추천 이력, 추천 점수, 추천 이유에 저장하거나 반영하지 않는다.

## 10. Recommendation API

추천 생성 API:

```http
POST /api/recommendations
Authorization: Bearer {accessToken}
Content-Type: application/json
```

Request body는 선택이다.

```json
{
  "situation": "WORK",
  "forecastPeriod": "AFTERNOON"
}
```

기본값:

- body가 없거나 `situation`이 누락되면 `CASUAL`
- body가 없거나 `forecastPeriod`가 누락되면 `CURRENT`

반복 호출 제한:

- `POST /api/recommendations`는 인증 사용자별 process-local fixed-window throttle을 적용한다.
- 기본 정책은 1분 window 안에서 user당 30회이며, 환경변수 `RECOMMENDATION_CREATION_THROTTLE_MAX_REQUESTS`, `RECOMMENDATION_CREATION_THROTTLE_WINDOW`로 조정한다.
- 제한을 초과하면 `429 Too Many Requests`와 `RECOMMENDATION_CREATION_LIMIT_EXCEEDED`로 실패한다.

추천 이력:

- `GET /api/recommendations?limit={limit}`
- 기본 `limit=20`, 최소 1, 최대 50
- 최신순 정렬

착용 완료와 피드백:

- `PATCH /api/recommendations/{recommendationId}/worn`
- `PUT /api/recommendations/{recommendationId}/feedback`
- 피드백 PUT은 전체 교체이며 누락 필드는 `null`로 간주한다.

MVP10 AI 분석 결과는 추천 API request/response field가 아니며 추천 계산에 사용하지 않는다.

## 11. 유지 API

위치, 날씨, 추천 API는 MVP7 계약을 유지한다. 옷 API는 기존 보관 처리에 보관함 조회와 보관 해제를 더해 현재 사용자 소유 옷만 다룬다.

- `GET /api/locations?keyword={keyword}`는 내부 KMA catalog 검색이다.
- `POST /api/locations/resolve`는 브라우저 좌표 원문을 저장하지 않는다.
- `GET /api/clothes`는 보관하지 않은 옷만 반환하고, `GET /api/clothes/archived`는 보관한 옷만 반환한다.
- `PATCH /api/clothes/{clothingId}/archive`와 `PATCH /api/clothes/{clothingId}/unarchive`는 멱등이다.
- `POST /api/recommendations`는 optional `situation`, `forecastPeriod`를 받을 수 있다.
- `WeatherResponse`와 `RecommendationResponse.weather`는 location/source metadata를 포함한다.
- 추천 피드백 PUT은 전체 교체이며 누락 필드는 `null`로 간주한다.
- `POST /api/clothes/analyze-image`는 유지 API를 대체하지 않고 옷 등록 전 후보 제안만 담당한다.

## 12. Error Codes

MVP10에서 추가하는 error code:

| Code | Status | Meaning |
| --- | --- | --- |
| `CLOTHING_ANALYSIS_DISABLED` | `503 Service Unavailable` | 옷 사진 분석 기능이 비활성 또는 API key 미설정 |
| `CLOTHING_ANALYSIS_UNAVAILABLE` | `503 Service Unavailable` | Spring AI/OpenAI provider 호출 실패 또는 timeout |
| `CLOTHING_ANALYSIS_LIMIT_EXCEEDED` | `429 Too Many Requests` | 사용자별 분석 일일 제한 초과 |

기존 주요 error code:

| Code | Status | Meaning |
| --- | --- | --- |
| `INVALID_REQUEST` | `400 Bad Request` | 도메인별 일반 요청 오류 또는 잘못된 image validation |
| `METHOD_ARGUMENT_NOT_VALID` | `400 Bad Request` | `MethodArgumentNotValidException`: request body DTO validation 실패 |
| `HANDLER_METHOD_VALIDATION` | `400 Bad Request` | `HandlerMethodValidationException`: controller method parameter validation 실패 |
| `CONSTRAINT_VIOLATION` | `400 Bad Request` | `ConstraintViolationException`: Bean Validation constraint violation |
| `MISSING_SERVLET_REQUEST_PARAMETER` | `400 Bad Request` | `MissingServletRequestParameterException`: 필수 request parameter 누락 |
| `MISSING_SERVLET_REQUEST_PART` | `400 Bad Request` | `MissingServletRequestPartException`: 필수 multipart part 누락 |
| `METHOD_ARGUMENT_TYPE_MISMATCH` | `400 Bad Request` | `MethodArgumentTypeMismatchException`: query/path parameter type mismatch |
| `HTTP_MESSAGE_NOT_READABLE` | `400 Bad Request` | `HttpMessageNotReadableException`: 읽을 수 없는 JSON request body |
| `INVALID_FORMAT` | `400 Bad Request` | `InvalidFormatException`: JSON request body 값 형식 오류 |
| `ILLEGAL_ARGUMENT` | `400 Bad Request` | `IllegalArgumentException`: 도메인/서비스 입력 인자 오류 |
| `MAX_UPLOAD_SIZE_EXCEEDED` | `400 Bad Request` | `MaxUploadSizeExceededException`: 업로드 크기 제한 초과 |
| `MULTIPART_EXCEPTION` | `400 Bad Request` | `MultipartException`: 기타 multipart 요청 오류 |
| `INVALID_PAGINATION` | `400 Bad Request` | 추천 이력 조회 pagination/limit 값 오류 |
| `UNAUTHORIZED` | `401 Unauthorized` | 인증 필요 |
| `INVALID_TOKEN` | `401 Unauthorized` | 인증 token 또는 refresh token 오류 |
| `EMAIL_VERIFICATION_REQUIRED` | `403 Forbidden` | 이메일 인증 전 password login 차단 |
| `ACCOUNT_TOKEN_INVALID` | `400 Bad Request` | 인증/재설정 token 없음, 만료, 사용 완료, 불일치 |
| `PASSWORD_LOGIN_DISABLED` | `400 Bad Request` | password login이 없는 계정에서 password flow 사용 |
| `LOGIN_ATTEMPT_LIMIT_EXCEEDED` | `429 Too Many Requests` | 같은 email/client key 또는 client key의 로그인 실패 반복 제한 초과 |
| `RECOMMENDATION_CREATION_LIMIT_EXCEEDED` | `429 Too Many Requests` | 사용자별 추천 생성 반복 호출 제한 초과 |
| `OAUTH2_PROVIDER_UNAVAILABLE` | `503 Service Unavailable` | Google OAuth 설정 없음 또는 비활성 |
| `CLOTHING_NOT_FOUND` | `404 Not Found` | 옷 없음 또는 다른 사용자 옷 접근 |
| `CLOTHING_IMAGE_NOT_FOUND` | `404 Not Found` | 옷 이미지 없음 |

추천 business failure code는 기존처럼 `422 Unprocessable Entity`를 사용한다.
