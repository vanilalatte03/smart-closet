# Frontend: SmartCloset MVP10 AI Clothing Registration Assist

## 목표

MVP10 프론트엔드는 MVP9 UI/UX 리디자인 완료 SPA 위에서 옷 등록/수정 화면에 사진 기반 AI 후보 체크 흐름을 추가한다.

AI는 form 값을 자동 저장하지 않는다. 사용자가 사진을 선택하고 `AI 후보 체크`를 직접 실행하면, 백엔드 `POST /api/clothes/analyze-image` 응답의 후보값을 form에 채운다. confidence가 낮은 필드는 흐리게 표시하고 `확인 필요` 상태로 둔다. 사용자가 수정하거나 확인한 최종 값만 기존 옷 저장 API로 저장한다.

추천 화면, 추천 점수, 후보 필터링, tie-break, 추천 이유는 기존 규칙 기반 흐름을 유지한다.

## 기술 기준

- 개발·테스트에는 Node.js 22.12 이상인 22.x 또는 24.x, 26 이상을 사용한다. Vitest 5의 런타임 요구사항이며 CI와 frontend Docker build는 Node.js 22를 사용한다.
- React
- Vite
- TypeScript strict
- 기존 CSS 체계 우선
- 큰 상태 관리 라이브러리 추가 금지
- API 요청/응답 DTO는 `src/types/api.ts`에 명시
- API 함수는 `src/api/smartClosetApi.ts`에서 정의
- Access token은 memory state에 저장
- Refresh token은 HttpOnly cookie로만 관리
- refresh cookie를 사용하는 요청은 credentials 포함
- 로그인 이메일 저장 체크박스는 이메일 주소 문자열만 브라우저 저장소에 저장할 수 있다.
- 보호 이미지 조회는 blob fetch와 object URL을 사용하고 cleanup한다.
- AI 분석 응답은 필요한 경우 파일 fingerprint 기준으로 브라우저 memory cache에만 재사용한다.

## 디자인 기준

MVP10 디자인 기준은 MVP9에서 정리한 공통 UI 원칙을 유지하고, 옷장 등록/수정 form에 AI 후보 체크 상태를 자연스럽게 추가하는 것이다.

공통 UI 원칙:

- 데스크톱 primary navigation은 상단 탭이다.
- 모바일 primary navigation은 하단 탭이다.
- primary nav는 `추천`, `옷장`, `내 취향`, `위치`, `기록`으로 고정한다.
- `계정 설정`은 주 navigation tab이 아니라 우측 상단 profile pill/menu에서 진입한다.
- 카드 radius는 8px 이하로 유지한다.
- 카드 안에 카드가 중첩되는 느낌을 피한다.
- 화면 section은 floating card보다 full-width band 또는 unframed layout을 우선한다.
- button은 명확한 command에만 text를 사용하고, tool/action에는 기존 icon library가 있으면 icon을 우선한다.
- 색상은 swatch, 소재와 style tag는 chip/toggle, 상황/예보 시간대는 segmented/card control을 우선한다.
- hero-scale type은 Auth 또는 화면 hero에만 사용하고 compact panel 내부 heading은 작고 단단하게 유지한다.
- 390px 모바일 폭에서 버튼, 카드, 입력 텍스트가 parent를 넘거나 서로 겹치지 않아야 한다.
- 앱 내부 문구는 사용자가 다음 행동을 결정하는 데 필요한 안내에 집중한다.

## Navigation

Authenticated shell:

- Desktop: top app bar + centered/left tab navigation
- Mobile: compact top header + bottom tab navigation
- Profile pill/menu: current user avatar/name, account settings 진입, logout 진입

Primary nav labels:

- `추천`
- `옷장`
- `내 취향`
- `위치`
- `기록`

Routing/state:

- 로그인 후 기본 view는 `추천`이다.
- 기존 app state 기반 view 전환을 유지한다.
- `account` view는 primary nav 목록에서 제외하고 profile action으로만 진입한다.
- 계정 삭제 또는 로그아웃 후 local auth state를 초기화하고 Auth view로 이동한다.

## 인증 상태 기준

상태:

- `restoring`: refresh session 확인 중
- `anonymous`: 로그인 필요
- `authenticated`: access token 보유
- `expired`: refresh 실패 후 세션 만료 안내

규칙:

- 앱 시작 시 access token이 없으면 `POST /api/auth/refresh`를 호출해 세션 복구를 시도한다.
- refresh 성공 시 access token과 current user를 memory state에 저장한다.
- refresh 실패 시 anonymous 상태로 전환한다.
- 보호 API가 `401`을 반환하면 refresh를 한 번 시도하고 원 요청을 한 번만 재시도한다.
- retry 이후에도 실패하면 access token을 제거하고 세션 만료 안내를 표시한다.
- 로그아웃은 `POST /api/auth/logout`을 호출한 뒤 local auth state를 초기화한다.
- `restoring` 화면은 layout shift가 작아야 한다.

## API Client 기준

MVP8에서 확정된 auth/account 함수와 기존 보호 API 함수를 유지한다.

- `signup(body)`
- `login(body)`
- `refreshSession()`
- `logout()`
- `requestEmailVerification(body)`
- `confirmEmailVerification(body)`
- `requestPasswordReset(body)`
- `confirmPasswordReset(body)`
- `getOAuthProviders()`
- `deleteAccount(accessToken, body)`
- `createClothing(accessToken, body)`
- `updateClothing(accessToken, clothingId, body)`
- `uploadClothingImage(accessToken, clothingId, file)`
- `fetchClothingImageBlob(accessToken, clothingId)`

MVP10에서 추가한다:

- `analyzeClothingImage(accessToken, file)`

요청 기준:

- 분석 API는 `multipart/form-data`로 `image` part를 전송한다.
- 분석 API도 보호 API이므로 Authorization header가 필요하다.
- 분석 실패가 기존 manual form 입력을 막지 않아야 한다.
- `CLOTHING_ANALYSIS_DISABLED`, `CLOTHING_ANALYSIS_UNAVAILABLE`, `CLOTHING_ANALYSIS_LIMIT_EXCEEDED`는 사용자에게 짧은 안내로 표시하고 manual 입력 상태를 유지한다.

Refresh cookie 요청:

- `fetch`에 `credentials: 'include'`를 사용한다.
- 계정 삭제 요청도 성공 응답의 refresh cookie 만료 `Set-Cookie`를 브라우저가 반영할 수 있도록 credentials를 포함한다.
- `AuthResponse`에 refresh token string이 없음을 전제로 한다.

이미지 blob fetch:

- Authorization header를 붙인다.
- object URL은 cleanup한다.
- 보호 이미지를 일반 public `<img src>`로 직접 참조하지 않는다.

## 타입 기준

```ts
export type AuthProvider = 'PASSWORD' | 'GOOGLE';

export type CurrentUserResponse = {
  email: string;
  name: string;
  role: 'USER';
  emailVerified: boolean;
  passwordLoginEnabled: boolean;
  authProviders: AuthProvider[];
  createdAt: string;
  updatedAt: string;
};

export type UpdateCurrentUserRequest = {
  name: string;
};

export type SignupResponse = {
  email: string;
  emailVerificationRequired: boolean;
  message: string;
};

export type AuthResponse = {
  accessToken: string;
  tokenType: 'Bearer';
  user: CurrentUserResponse;
};

export type AccountDeletionRequest = {
  confirmation: 'DELETE';
  password?: string;
};
```

MVP5/MVP6/MVP7 위치, 날씨, 옷, 추천 타입과 MVP8 account/auth 타입은 유지한다.

MVP10에서 추가하는 옷 분석 타입:

```ts
export type ClothingAnalysisField =
  | 'name'
  | 'category'
  | 'color'
  | 'material'
  | 'minTemperature'
  | 'maxTemperature'
  | 'rainSuitable'
  | 'styleTags';

export type ClothingAnalysisSuggestion = {
  name: string;
  category: ClothingCategory;
  color: ClothingColor;
  material: ClothingMaterial;
  minTemperature: number;
  maxTemperature: number;
  rainSuitable: boolean;
  styleTags: string[];
};

export type ClothingAnalysisResponse = {
  analyzable: boolean;
  suggestion: ClothingAnalysisSuggestion | null;
  fieldConfidence: Partial<Record<ClothingAnalysisField, number>>;
  reviewRequiredFields: ClothingAnalysisField[];
  lowConfidenceThreshold: number;
};
```

## Closet View

기존 UX 기준:

- 목록은 이미지 중심 card/list로 구성한다.
- 데스크톱 옷장 목록은 한 줄 4개 고정 grid로 카드 크기를 균일하게 유지하고, 모바일은 2열 grid로 과도하게 큰 카드를 피한다.
- category, image presence, tag presence filter를 chip으로 제공할 수 있다.
- 옷 추가 CTA는 데스크톱과 모바일 모두 hover 없이 접근 가능해야 한다.
- 옷장 목록 action 영역의 `보관함` 버튼으로 보관한 옷 목록을 열고, 보관한 옷은 `다시 꺼내기`로 추천 후보에 복귀시킨다.
- 모바일은 옷장 진입 시 목록을 먼저 보여주고, 옷 추가 CTA 또는 수정 버튼으로 등록/수정 화면에 진입한다.
- 등록/수정 form은 이미지 업로드, 이름, 카테고리, 색상, 소재, 기온 범위, 비 적합성, style tag를 한 흐름으로 제공한다.
- 이미지 업로드 입력은 파일 선택, 드래그 앤 드롭, 클립보드 이미지 붙여넣기를 같은 파일 검증 규칙으로 처리한다.
- 이미지 업로드 실패는 옷 정보 저장 실패와 분리해서 안내한다.
- 기존 옷 등록/수정 JSON API를 multipart로 대체하지 않는다.

MVP10 AI 후보 체크 UX:

- 이미지 선택 후 preview 근처에 `AI 후보 체크` command를 제공한다.
- 분석은 사용자가 버튼을 눌렀을 때만 실행한다.
- 버튼은 이미지가 없거나 분석 중이면 비활성화한다.
- 분석 중에는 form layout이 흔들리지 않는 progress 상태를 보여준다.
- 같은 파일은 name, size, lastModified 또는 content hash 기반 fingerprint로 마지막 분석 결과를 재사용할 수 있다.
- `analyzable=false`면 후보를 채우지 않고 manual 입력을 유지한다.
- `suggestion`이 있으면 기존 form field에 후보값을 채운다.
- 기존 사용자가 이미 수정한 field를 덮어쓸 때는 분석 적용 동작이 명확해야 한다.
- `reviewRequiredFields`에 포함된 field는 흐린 시각 상태와 `확인 필요` 표시를 함께 둔다.
- 사용자가 field를 직접 수정하면 해당 field의 확인 필요 상태를 제거한다.
- 사용자가 별도 확인 action을 누르면 값 변경 없이 해당 field의 확인 필요 상태를 제거할 수 있다.
- 확인 필요 field가 남아 있는 상태에서 저장하면 한 번 더 확인한다.
- 최종 저장은 기존 `POST /api/clothes` 또는 `PUT /api/clothes/{clothingId}` JSON API를 사용한다.
- 선택 이미지가 있으면 기존 `PUT /api/clothes/{clothingId}/image`를 사용한다.
- 분석 결과 자체는 local UI state 또는 fingerprint cache에만 두고 recommendation state로 전달하지 않는다.

Low confidence 표시:

- field label 또는 control 주변에 작은 `확인 필요` 상태를 둔다.
- 흐림은 읽기 어려울 정도로 낮추지 않는다.
- 모바일 390px에서 badge, helper text, control이 겹치지 않게 줄바꿈한다.
- 저장 confirmation은 공포스럽게 만들지 않고 "아직 확인 필요 항목이 있습니다" 수준으로 안내한다.

## Auth View

제공 flow:

- 로그인
- 로그인 이메일 저장 체크박스
- 회원가입
- 이메일 인증 안내와 인증 재요청
- 인증번호 확인
- 비밀번호 재설정 요청
- 비밀번호 재설정 확인
- Google login button 또는 disabled 상태

UX 기준:

- 넓은 visual background와 중앙 form 구조를 우선한다.
- 모바일에서는 visual이 form 가독성을 방해하지 않아야 한다.
- 회원가입 성공 후 "이메일 인증 후 로그인할 수 있습니다" 상태를 보여준다.
- local 개발에서 outbox email sender를 사용한다는 문구는 문서/개발 안내에만 둔다.
- 미인증 계정 로그인 실패는 인증 재요청으로 이어질 수 있어야 한다.
- 비밀번호 재설정 요청은 계정 존재 여부를 노출하지 않는 중립 성공 메시지를 보여준다.
- Google provider disabled 상태면 button을 비활성화하고 설정 필요 상태를 작게 표시한다.
- 이메일 저장 체크박스를 선택하면 로그인 성공 후 이메일 입력값만 저장하고 다음 Auth view 진입 시 이메일 input에 복원한다.
- 이메일 저장 체크박스를 해제한 상태로 로그인하면 저장된 이메일 값을 제거한다.

## Recommendation View

UX 기준:

- 추천 화면은 로그인 후 기본 view다.
- 날씨, 위치, 상황, 예보 시간대, 옷장 준비 상태, 최근 이력을 한 화면에서 스캔할 수 있어야 한다.
- Hero band는 오늘의 핵심 추천 메시지와 현재 위치/날씨/예보 시간대를 보여준다.
- 상황과 예보 시간대 선택은 segmented/card control로 제공한다.
- 추천 결과는 옷 이미지와 조합 이름, "오늘 입기 좋은 이유"를 먼저 보여준다.
- KMA 격자, provider, fallback, base/forecast time 같은 내부 날씨 source 상세는 일반 화면에 노출하지 않는다.
- 점수 상세는 보조 panel로 제공한다.
- 착용 완료와 피드백 저장/clear UX를 유지한다.
- 추천 실패는 내부 failure code보다 한국어 안내와 해결 CTA를 우선 표시한다.
- AI 분석 결과와 confidence를 추천 화면에 표시하지 않는다.

## Preferences View

UX 기준:

- 선호 색상은 swatch로 표시한다.
- 선호 소재는 toggle 또는 chip control로 표시한다.
- style tag는 chip 입력과 제거 control을 제공한다.
- 추천 영향은 보조 panel로 표시하되 점수 계산 규칙을 새로 만들지 않는다.
- blank style tag는 저장하지 않는다.

## Location View

UX 기준:

- 현재 저장 위치를 hero 또는 status band에서 명확히 보여준다.
- 동네 검색과 현재 위치 후보 찾기는 분리한다.
- 현재 위치 후보 찾기는 브라우저 Geolocation과 서버 `POST /api/locations/resolve`를 사용한다.
- 좌표는 저장하지 않고 선택한 동네만 계정 위치로 남는다는 안내를 보여준다.
- 외부 지도/주소 API와 지도 UI를 추가하지 않는다.

## History View

UX 기준:

- 추천 이력은 최신순을 유지한다.
- 상단은 calendar strip, 본문은 날짜 흐름 timeline grouping으로 구성할 수 있다.
- outfit image grouping이 먼저 보이고, 날씨/위치 요약과 피드백은 보조 정보로 표시한다.
- 첫 화면은 outfit, 착용/피드백 상태, 핵심 이유 중심으로 미니멀하게 유지하고 점수/옷 상세는 상세보기 안에 둘 수 있다.
- provider, fallback, base/forecast time 같은 내부 날씨 source 상세는 History 상세보기에서도 노출하지 않는다.
- 삭제된 이미지 또는 이미지 없는 옷은 fallback visual로 표시한다.
- 현재 위치 변경 후에도 과거 이력 snapshot이 독립적으로 보인다는 점을 유지한다.

## Account Settings UX

진입:

- primary nav가 아니라 우측 상단 profile pill/menu에서 진입한다.

표시 항목:

- 이메일
- 이름과 이름 수정 진입
- 이메일 인증 상태
- 연결된 로그인 제공자: password, Google
- password login 가능 여부
- 세션 상태
- 계정 정보 카드 오른쪽 아래의 작은 진입 버튼으로 여는 계정 삭제 팝업

계정 삭제:

- 기본 화면에서는 큰 위험 영역을 바로 노출하지 않고 계정 정보 카드 오른쪽 아래 작은 계정 삭제 버튼으로 팝업을 연다.
- 삭제 전 확인 문구를 요구한다.
- Password login enabled 계정은 현재 비밀번호 입력을 요구한다.
- Google-only 계정은 confirmation만 요구한다.
- 삭제 성공 후 local auth state를 초기화하고 로그인 화면으로 이동한다.
- 삭제 성공 응답의 refresh cookie 만료 header가 브라우저에 반영되도록 삭제 API 호출에는 credentials를 포함한다.
- 삭제 실패 시 공통 error banner를 사용한다.
- 계정 삭제 팝업은 다른 설정 화면 위에 시각적으로 분리해서 표시한다.

## 기존 UX 유지

- MVP8 세션 복구, 이메일 인증, 비밀번호 재설정, Google provider 상태, 계정 삭제 UX를 유지한다.
- MVP9 primary navigation과 화면 밀도 기준을 유지한다.
- Location view의 동네 검색, 현재 위치 후보 찾기, 사용자용 위치 요약 표시를 유지한다.
- Recommendation view의 상황/예보 시간대 선택과 추천 결과 날씨 요약 표시를 유지한다.
- History view의 위치/날씨 snapshot은 사용자용 요약으로 표시한다.
- Closet image blob fetch와 object URL cleanup을 유지한다.
- Feedback UX를 유지한다.

## 금지사항

- Access token을 `localStorage`나 `sessionStorage`에 저장하지 마라. 이유: 현재 세션 기준은 memory state와 refresh cookie다.
- Refresh token 값을 JavaScript state나 JSON body에 저장하지 마라. 이유: refresh token은 HttpOnly cookie 전용이다.
- 이메일 저장 기능으로 비밀번호, access token, refresh token, current user object를 저장하지 마라. 이유: 편의 기능은 이메일 주소 문자열에만 한정한다.
- 큰 state-management library를 추가하지 마라. 이유: 현재 앱은 React state와 작은 hook으로 충분하다.
- 계정 설정을 primary nav tab으로 추가하지 마라. 이유: navigation 계약은 profile pill/menu 진입이다.
- AWS/S3/SES 전용 UI를 추가하지 마라. 이유: AWS 배포는 후속 MVP 범위다.
- 옷 분석 결과를 사용자가 확인하지 않은 채 저장하지 마라. 이유: AI는 후보 제안 보조만 담당한다.
- 분석 결과를 추천 화면, 추천 점수, 추천 이유, 추천 이력에 연결하지 마라. 이유: 추천은 규칙 기반 계약이다.
- 이미지 선택만으로 자동 분석 호출을 실행하지 마라. 이유: 비용은 사용자 수동 command와 daily limit으로 방어한다.
