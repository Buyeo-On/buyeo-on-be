# API 명세

[openapi.yaml](./raw/openapi.yaml)

> **버전 1.0.0** · OpenAPI 3.1.0 · 기준 파일 `openapi.yaml`

관광데이터 유즈케이스와 도메인 모델 및 규칙을 기준으로 작성한 API 명세다.
모든 응답은 success와 data를 가지는 공통 객체 형식을 사용한다.

## 1. 기본 정보

- **Base URL:** `https://api.buyeoon.example.com/v1`
- **데이터 형식:** 별도 표기가 없으면 `application/json`
- **인증:** 기본적으로 `Authorization: Bearer {accessToken}` 헤더 사용
- **공개 API:** 소셜 로그인, 토큰 갱신, 현재 약관 목록 조회
- **시간:** ISO 8601 date-time
- **식별자:** UUID

### 공통 성공 응답

```json
{
  "success": true,
  "data": {
    "...": "응답 데이터"
  }
}
```

### 공통 오류 응답

```json
{
  "success": false,
  "data": {
    "code": "LOCATION_VERIFICATION_FAILED",
    "message": "위치 인증에 실패했습니다."
  }
}
```

| 상태 코드 | 의미 |
| --- | --- |
| `400` | 잘못된 요청 또는 유효성 검증 실패 |
| `401` | 인증 필요 또는 인증 실패 |
| `403` | 권한 또는 위치 조건 미충족 |
| `404` | 대상을 찾을 수 없음 |
| `409` | 멱등성 키를 다른 요청에 재사용했거나 허용되지 않은 상태 전이 또는 소셜 계정 연결 충돌 |
| `413` | 업로드 파일 크기 초과 |

### 사진 미션 업로드 흐름

1. `POST /mission-photos/presigned-url`로 `photoId`, `uploadUrl`, 필수 헤더를 발급받는다.
2. API 인증 헤더나 multipart 형식 없이 `uploadUrl`로 파일 원본 바이트를 S3에 직접 `PUT`한다.
3. S3가 `200`을 반환하면 `POST /missions/{missionId}/submissions` 요청에 `photoId`를 넣는다.
4. 서버가 S3 객체의 소유자, 실제 크기, Content-Type을 확인하고 미션을 처리한다.

```javascript
PUT {uploadUrl} HTTP/1.1
Content-Type: image/jpeg

<raw binary image data>
```

### 멱등성 헤더

```javascript
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
```

- 같은 회원이 같은 키와 같은 요청 본문으로 다시 요청하면 최초 성공 응답과 같은 상태 코드 및 응답 본문을 반환한다.
- 중복 요청은 새로운 회원, 여행, 미션 완료, 방문 기록, 포인트 내역 또는 정산 결과를 만들지 않는다.
- 같은 키를 다른 요청 본문에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.
- 허용되지 않은 상태 전이도 `409`를 반환하며, 단순 재시도와 구분할 수 있도록 오류 코드를 제공한다.

### 커서 페이지네이션

`cursor`와 `size`를 사용하며 `size`의 기본값은 20, 허용 범위는 1~100이다.

## 2. API 목록

### Auth

| Method | Endpoint | 설명 |
| --- | --- | --- |
| POST | `/auth/social-login` | 소셜 로그인 또는 가입 |
| POST | `/auth/refresh` | 인증 토큰 갱신 |
| POST | `/auth/logout` | 로그아웃 |

### Members

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | `/members/me` | 내 회원 정보 조회 |
| DELETE | `/members/me` | 회원 탈퇴 |
| POST | `/members/me/social-accounts` | 다른 소셜 계정 연결 |
| PATCH | `/members/me/profile` | 프로필 수정 |
| GET | `/members/me/settings` | 서비스 설정 조회 |
| PATCH | `/members/me/settings` | 서비스 설정 변경 |

### Terms

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | `/terms` | 현재 약관 목록 조회 |
| PUT | `/members/me/term-consents` | 약관 동의 저장 |

### CitizenCards

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | `/citizen-cards/options` | 군민증 캐릭터와 테마 목록 조회 |
| POST | `/citizen-cards` | 디지털 군민증 생성 |
| GET | `/citizen-cards/me` | 내 디지털 군민증 조회 |
| GET | `/citizen-cards/me/barcode` | 군민증 바코드 조회 |

### Trips

| Method | Endpoint | 설명 |
| --- | --- | --- |
| POST | `/trips` | 여행 시작 |
| GET | `/trips/current` | 진행 중 여행 조회 |
| POST | `/trips/{tripId}/end` | 여행 종료 |
| PUT | `/trips/{tripId}/settlement` | 여행 포인트 정산 |
| GET | `/trips/{tripId}/statistics` | 여행 통계 조회 |
| GET | `/trips/{tripId}/footprint` | 여행 발자취 조회 |

### Places

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | `/places` | 주변 장소 탐색 |
| GET | `/places/{placeId}` | 장소 상세 조회 |
| GET | `/members/me/saved-places` | 저장한 장소 목록 조회 |
| PUT | `/members/me/saved-places/{placeId}` | 장소 저장 |
| DELETE | `/members/me/saved-places/{placeId}` | 저장한 장소 삭제 |

### Missions

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | `/missions/nearby` | 주변 미션 조회 |
| GET | `/missions/{missionId}` | 미션 상세 조회 |
| POST | `/mission-photos/presigned-url` | 사진 인증 이미지 업로드 URL 발급 |
| POST | `/missions/{missionId}/submissions` | 미션 답안 또는 사진 인증 제출 |

### Points

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | `/members/me/points` | 포인트 잔액 조회 |
| GET | `/members/me/point-transactions` | 포인트 내역 조회 |

### Badges

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | `/members/me/badges` | 배지 진척도 목록 조회 |
| GET | `/members/me/badges/{badgeId}` | 배지 상세 조회 |

### Notifications

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | `/members/me/notifications` | 알림 목록 조회 |
| PATCH | `/members/me/notifications/{notificationId}` | 알림 읽음 처리 |

## 3. API 상세

## 3.1 Auth

### POST `/auth/social-login` — 소셜 로그인 또는 가입

- **Operation ID:** `socialLogin`
- **인증:** 불필요

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `provider` | SocialProvider | 필수 | - |
| `authorizationCode` | string | 필수 | 길이 1~∞ |

#### 요청 예시

```javascript
POST /v1/auth/social-login HTTP/1.1
Host: api.buyeoon.example.com
Content-Type: application/json

{
  "provider": "KAKAO",
  "authorizationCode": "social-authorization-code"
}
```

#### 성공 응답 예시 — `200` 인증 성공

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.access-token",
    "refreshToken": "refresh-token-value",
    "expiresInSeconds": 3600,
    "isNewMember": true,
    "member": {
      "memberId": "550e8400-e29b-41d4-a716-446655440000",
      "status": "ACTIVE",
      "provider": "KAKAO",
      "displayName": null,
      "characterId": null,
      "requiredTermsAgreed": false,
      "citizenCardIssued": false,
      "createdAt": "2026-08-06T15:30:00+09:00"
    }
  }
}
```

**응답 코드:** `200` 인증 성공 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `409` 허용되지 않은 인증 상태

---

### POST `/auth/refresh` — 인증 토큰 갱신

- **Operation ID:** `refreshToken`
- **인증:** 불필요

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `refreshToken` | string | 필수 | - |

#### 요청 예시

```javascript
POST /v1/auth/refresh HTTP/1.1
Host: api.buyeoon.example.com
Content-Type: application/json

{
  "refreshToken": "refresh-token-value"
}
```

#### 성공 응답 예시 — `200` 인증 성공

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.new-access-token",
    "refreshToken": "rotated-refresh-token-value",
    "expiresInSeconds": 3600,
    "isNewMember": false,
    "member": {
      "memberId": "550e8400-e29b-41d4-a716-446655440000",
      "status": "ACTIVE",
      "provider": "KAKAO",
      "displayName": "부여여행자",
      "characterId": "550e8400-e29b-41d4-a716-446655440001",
      "requiredTermsAgreed": true,
      "citizenCardIssued": true,
      "createdAt": "2026-08-01T09:00:00+09:00"
    }
  }
}
```

**응답 코드:** `200` 인증 성공 · `401` 인증 필요 또는 인증 실패

---

### POST `/auth/logout` — 로그아웃

- **Operation ID:** `logout`
- **인증:** Bearer JWT 필요

#### 요청 예시

```javascript
POST /v1/auth/logout HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 처리 성공

```json
{
  "success": true,
  "data": {}
}
```

**응답 코드:** `200` 처리 성공 · `401` 인증 필요 또는 인증 실패

---

## 3.2 Members

### GET `/members/me` — 내 회원 정보 조회

- **Operation ID:** `getMyMember`
- **인증:** Bearer JWT 필요

#### 요청 예시

```javascript
GET /v1/members/me HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 회원 정보

```json
{
  "success": true,
  "data": {
    "memberId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "ACTIVE",
    "provider": "KAKAO",
    "displayName": "부여여행자",
    "characterId": "550e8400-e29b-41d4-a716-446655440000",
    "requiredTermsAgreed": true,
    "citizenCardIssued": true,
    "createdAt": "2026-08-06T15:30:00+09:00"
  }
}
```

**응답 코드:** `200` 회원 정보 · `401` 인증 필요 또는 인증 실패

---

### DELETE `/members/me` — 회원 탈퇴

- **Operation ID:** `withdrawMember`
- **인증:** Bearer JWT 필요
- **멱등성:** `Idempotency-Key` 필수

#### 요청 예시

```javascript
DELETE /v1/members/me HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
```

#### 성공 응답 예시 — `200` 처리 성공

```json
{
  "success": true,
  "data": {}
}
```

**응답 코드:** `200` 처리 성공 · `401` 인증 필요 또는 인증 실패 · `409` 멱등성 키를 다른 요청에 재사용했거나 허용되지 않은 상태 전이

---

### POST `/members/me/social-accounts` — 다른 소셜 계정 연결

현재 로그인 회원에게 OAuth 인증을 마친 소셜 계정을 연결한다. 이미 현재 회원에게 연결된 동일한 소셜 계정은 성공으로 처리한다.
- **Operation ID:** `linkSocialAccount`
- **인증:** Bearer JWT 필요

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `provider` | SocialProvider | 필수 | 연결할 소셜 로그인 제공자 |
| `authorizationCode` | string | 필수 | OAuth 인증 코드, 최소 1자 |

#### 요청 예시

```javascript
POST /v1/members/me/social-accounts HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "provider": "APPLE",
  "authorizationCode": "social-authorization-code"
}
```

#### 성공 응답 예시 — `200` 처리 성공

```json
{
  "success": true,
  "data": {}
}
```

- 이미 현재 회원에게 연결된 동일한 소셜 계정은 `200`으로 처리한다.
- 해당 소셜 계정이 다른 회원에게 연결되어 있거나 현재 회원에게 같은 제공자의 다른 계정이 연결되어 있으면 `409`를 반환한다.
**응답 코드:** `200` 처리 성공 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `409` 소셜 계정 연결 충돌

---

### PATCH `/members/me/profile` — 프로필 수정

- **Operation ID:** `updateMyProfile`
- **인증:** Bearer JWT 필요

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `displayName` | string | 선택 | 길이 1~8 |
| `characterId` | string (uuid) | 선택 | - |

#### 요청 예시

```javascript
PATCH /v1/members/me/profile HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "displayName": "부여여행자",
  "characterId": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### 성공 응답 예시 — `200` 회원 정보

```json
{
  "success": true,
  "data": {
    "memberId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "ACTIVE",
    "provider": "KAKAO",
    "displayName": "부여여행자",
    "characterId": "550e8400-e29b-41d4-a716-446655440000",
    "requiredTermsAgreed": true,
    "citizenCardIssued": true,
    "createdAt": "2026-08-06T15:30:00+09:00"
  }
}
```

**응답 코드:** `200` 회원 정보 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패

---

### GET `/members/me/settings` — 서비스 설정 조회

- **Operation ID:** `getMySettings`
- **인증:** Bearer JWT 필요

#### 요청 예시

```javascript
GET /v1/members/me/settings HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 서비스 설정

```json
{
  "success": true,
  "data": {
    "nearbyQuizNotificationEnabled": true,
    "darkModeEnabled": true,
    "version": 0
  }
}
```

**응답 코드:** `200` 서비스 설정 · `401` 인증 필요 또는 인증 실패

---

### PATCH `/members/me/settings` — 서비스 설정 변경

- **Operation ID:** `updateMySettings`
- **인증:** Bearer JWT 필요

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `nearbyQuizNotificationEnabled` | boolean | 선택 | - |
| `darkModeEnabled` | boolean | 선택 | - |
| `deviceNotificationPermissionGranted` | boolean | 선택 | - |
| `deviceLocationPermissionGranted` | boolean | 선택 | - |
| `version` | integer | 필수 | 최솟값 0 |

#### 요청 예시

```javascript
PATCH /v1/members/me/settings HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "nearbyQuizNotificationEnabled": true,
  "darkModeEnabled": true,
  "deviceNotificationPermissionGranted": true,
  "deviceLocationPermissionGranted": true,
  "version": 0
}
```

#### 성공 응답 예시 — `200` 서비스 설정

```json
{
  "success": true,
  "data": {
    "nearbyQuizNotificationEnabled": true,
    "darkModeEnabled": true,
    "version": 0
  }
}
```

**응답 코드:** `200` 서비스 설정 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `409` 설정 버전 충돌 또는 허용되지 않은 상태 전이

---

## 3.3 Terms

### GET `/terms` — 현재 약관 목록 조회

- **Operation ID:** `getTerms`
- **인증:** 불필요

#### 요청 예시

```javascript
GET /v1/terms HTTP/1.1
Host: api.buyeoon.example.com
```

#### 성공 응답 예시 — `200` 약관 목록

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "termId": "550e8400-e29b-41d4-a716-446655440000",
        "type": "SERVICE",
        "version": "1.0",
        "required": true,
        "title": "서비스 이용약관",
        "content": "약관 본문",
        "effectiveAt": "2026-08-01T00:00:00+09:00"
      }
    ]
  }
}
```

**응답 코드:** `200` 약관 목록

---

### PUT `/members/me/term-consents` — 약관 동의 저장

- **Operation ID:** `updateTermConsents`
- **인증:** Bearer JWT 필요
- **멱등성:** `Idempotency-Key` 필수

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `consents` | TermConsentItem[] | 필수 | 최소 1개 |

#### 요청 예시

```javascript
PUT /v1/members/me/term-consents HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
Content-Type: application/json

{
  "consents": [
    {
      "termId": "550e8400-e29b-41d4-a716-446655440000",
      "version": "string",
      "agreed": true
    }
  ]
}
```

#### 성공 응답 예시 — `200` 약관 동의 결과

```json
{
  "success": true,
  "data": {
    "requiredTermsAgreed": true,
    "agreedAt": "2026-08-06T15:30:00+09:00"
  }
}
```

**응답 코드:** `200` 약관 동의 결과 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `409` 멱등성 키를 다른 요청에 재사용했거나 허용되지 않은 상태 전이

---

## 3.4 CitizenCards

### GET `/citizen-cards/options` — 군민증 캐릭터와 테마 목록 조회

- **Operation ID:** `getCitizenCardOptions`
- **인증:** Bearer JWT 필요

#### 요청 예시

```javascript
GET /v1/citizen-cards/options HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 군민증 선택 항목

```json
{
  "success": true,
  "data": {
    "characters": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "부소산성",
        "imageUrl": "https://cdn.buyeoon.example.com/example.png"
      }
    ],
    "themes": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "부소산성",
        "imageUrl": "https://cdn.buyeoon.example.com/example.png"
      }
    ]
  }
}
```

**응답 코드:** `200` 군민증 선택 항목 · `401` 인증 필요 또는 인증 실패

---

### POST `/citizen-cards` — 디지털 군민증 생성

신규 회원이 필수 약관 동의를 완료하고 현재 위치가 부여 안임을 확인한 후 디지털 군민증을 생성한다.
- **Operation ID:** `createCitizenCard`
- **인증:** Bearer JWT 필요
- **멱등성:** `Idempotency-Key` 필수

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `displayName` | string | 필수 | 길이 1~8 |
| `characterId` | string (uuid) | 필수 | - |
| `themeId` | string (uuid) | 필수 | - |
| `location` | Location | 필수 | - |

#### 요청 예시

```javascript
POST /v1/citizen-cards HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
Content-Type: application/json

{
  "displayName": "부여여행자",
  "characterId": "550e8400-e29b-41d4-a716-446655440000",
  "themeId": "550e8400-e29b-41d4-a716-446655440000",
  "location": {
    "latitude": 36.2754,
    "longitude": 126.9098,
    "accuracyMeters": 8.5,
    "capturedAt": "2026-08-06T15:30:00+09:00"
  }
}
```

#### 성공 응답 예시 — `201` 디지털 군민증

```json
{
  "success": true,
  "data": {
    "cardId": "550e8400-e29b-41d4-a716-446655440000",
    "displayName": "부여여행자",
    "character": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "부소산성",
      "imageUrl": "https://cdn.buyeoon.example.com/example.png"
    },
    "theme": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "부소산성",
      "imageUrl": "https://cdn.buyeoon.example.com/example.png"
    },
    "issuedAt": "2026-08-06T15:30:00+09:00"
  }
}
```

**응답 코드:** `201` 디지털 군민증 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `403` 권한 또는 위치 조건 미충족 · `409` 멱등성 키를 다른 요청에 재사용했거나 허용되지 않은 상태 전이

---

### GET `/citizen-cards/me` — 내 디지털 군민증 조회

- **Operation ID:** `getMyCitizenCard`
- **인증:** Bearer JWT 필요

#### 요청 예시

```javascript
GET /v1/citizen-cards/me HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 디지털 군민증

```json
{
  "success": true,
  "data": {
    "cardId": "550e8400-e29b-41d4-a716-446655440000",
    "displayName": "부여여행자",
    "character": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "부소산성",
      "imageUrl": "https://cdn.buyeoon.example.com/example.png"
    },
    "theme": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "부소산성",
      "imageUrl": "https://cdn.buyeoon.example.com/example.png"
    },
    "issuedAt": "2026-08-06T15:30:00+09:00"
  }
}
```

**응답 코드:** `200` 디지털 군민증 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

### GET `/citizen-cards/me/barcode` — 군민증 바코드 조회

MVP 시연용 바코드를 반환하며 포인트를 차감하지 않는다.
- **Operation ID:** `getMyCitizenCardBarcode`
- **인증:** Bearer JWT 필요

#### 요청 예시

```javascript
GET /v1/citizen-cards/me/barcode HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 군민증 바코드

```json
{
  "success": true,
  "data": {
    "citizenCard": {
      "cardId": "550e8400-e29b-41d4-a716-446655440000",
      "displayName": "부여여행자",
      "character": {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "부소산성",
        "imageUrl": "https://cdn.buyeoon.example.com/example.png"
      },
      "theme": {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "부소산성",
        "imageUrl": "https://cdn.buyeoon.example.com/example.png"
      },
      "issuedAt": "2026-08-06T15:30:00+09:00"
    },
    "barcodeValue": "BUYEOON-0123456789",
    "pointBalance": 1200,
    "simulationOnly": true,
    "notice": "실제 상점에서의 사용은 제한됩니다."
  }
}
```

**응답 코드:** `200` 군민증 바코드 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

## 3.5 Trips

### POST `/trips` — 여행 시작

필수 약관에 동의하고 디지털 군민증을 발급받은 회원이 부여 안에서 여행을 시작한다.
- **Operation ID:** `startTrip`
- **인증:** Bearer JWT 필요
- **멱등성:** `Idempotency-Key` 필수

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `location` | Location | 필수 | - |

#### 요청 예시

```javascript
POST /v1/trips HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
Content-Type: application/json

{
  "location": {
    "latitude": 36.2754,
    "longitude": 126.9098,
    "accuracyMeters": 8.5,
    "capturedAt": "2026-08-06T15:30:00+09:00"
  }
}
```

#### 성공 응답 예시 — `201` 여행 정보

```json
{
  "success": true,
  "data": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "IN_PROGRESS",
    "startedAt": "2026-08-06T10:00:00+09:00",
    "endedAt": null,
    "settledAt": null
  }
}
```

**응답 코드:** `201` 여행 정보 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `403` 권한 또는 위치 조건 미충족 · `409` 멱등성 키를 다른 요청에 재사용했거나 허용되지 않은 상태 전이

---

### GET `/trips/current` — 진행 중 여행 조회

- **Operation ID:** `getCurrentTrip`
- **인증:** Bearer JWT 필요

#### 요청 예시

```javascript
GET /v1/trips/current HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 여행 정보

```json
{
  "success": true,
  "data": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "IN_PROGRESS",
    "startedAt": "2026-08-06T10:00:00+09:00",
    "endedAt": null,
    "settledAt": null
  }
}
```

**응답 코드:** `200` 여행 정보 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

### POST `/trips/{tripId}/end` — 여행 종료

- **Operation ID:** `endTrip`
- **인증:** Bearer JWT 필요
- **멱등성:** `Idempotency-Key` 필수

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `tripId` | 필수 | string (uuid) | - |

#### 요청 예시

```javascript
POST /v1/trips/550e8400-e29b-41d4-a716-446655440000/end HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
```

#### 성공 응답 예시 — `200` 여행 정보

```json
{
  "success": true,
  "data": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "ENDED",
    "startedAt": "2026-08-06T10:00:00+09:00",
    "endedAt": "2026-08-06T15:30:00+09:00",
    "settledAt": null
  }
}
```

**응답 코드:** `200` 여행 정보 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음 · `409` 멱등성 키를 다른 요청에 재사용했거나 허용되지 않은 상태 전이

---

### PUT `/trips/{tripId}/settlement` — 여행 포인트 정산

지정한 여행에서 적립하고 아직 정산하지 않은 포인트만 정산한다. 다른 여행에서 이월한 포인트에는 영향을 주지 않는다.
- **Operation ID:** `settleTripPoints`
- **인증:** Bearer JWT 필요
- **멱등성:** `Idempotency-Key` 필수

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `tripId` | 필수 | string (uuid) | - |

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `choice` | SettlementChoice | 필수 | - |

#### 요청 예시

```javascript
PUT /v1/trips/550e8400-e29b-41d4-a716-446655440000/settlement HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
Content-Type: application/json

{
  "choice": "LEAVE_TO_BUYEO"
}
```

#### 성공 응답 예시 — `200` 여행 포인트 정산 결과

```json
{
  "success": true,
  "data": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "choice": "LEAVE_TO_BUYEO",
    "settledPoints": 300,
    "remainingBalance": 500,
    "expiresAt": null,
    "settledAt": "2026-08-06T15:35:00+09:00"
  }
}
```

**응답 코드:** `200` 여행 포인트 정산 결과 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음 · `409` 멱등성 키를 다른 요청에 재사용했거나 허용되지 않은 상태 전이

---

### GET `/trips/{tripId}/statistics` — 여행 통계 조회

탐방 시간은 여행 시작부터 종료 시각까지 계산하며, 진행 중인 여행은 현재 시각까지 계산한다. MVP에서 이동 거리와 소모 칼로리는 `null`이다.
- **Operation ID:** `getTripStatistics`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `tripId` | 필수 | string (uuid) | - |

#### 요청 예시

```javascript
GET /v1/trips/550e8400-e29b-41d4-a716-446655440000/statistics HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 여행 통계

```json
{
  "success": true,
  "data": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "distanceKm": null,
    "visitedPlaceCount": 4,
    "durationMinutes": 330,
    "caloriesKcal": null
  }
}
```

**응답 코드:** `200` 여행 통계 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

### GET `/trips/{tripId}/footprint` — 여행 발자취 조회

- **Operation ID:** `getTripFootprint`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `tripId` | 필수 | string (uuid) | - |

#### 요청 예시

```javascript
GET /v1/trips/550e8400-e29b-41d4-a716-446655440000/footprint HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 여행 발자취

```json
{
  "success": true,
  "data": {
    "trip": {
      "tripId": "550e8400-e29b-41d4-a716-446655440000",
      "status": "SETTLED",
      "startedAt": "2026-08-06T10:00:00+09:00",
      "endedAt": "2026-08-06T15:30:00+09:00",
      "settledAt": "2026-08-06T15:35:00+09:00"
    },
    "statistics": {
      "tripId": "550e8400-e29b-41d4-a716-446655440000",
      "distanceKm": null,
      "visitedPlaceCount": 1,
      "durationMinutes": 330,
      "caloriesKcal": null
    },
    "visits": [
      {
        "visitId": "550e8400-e29b-41d4-a716-446655440010",
        "missionId": "550e8400-e29b-41d4-a716-446655440011",
        "visitedAt": "2026-08-06T11:20:00+09:00",
        "place": {
          "placeId": "550e8400-e29b-41d4-a716-446655440012",
          "category": "HERITAGE",
          "name": "부소산성",
          "latitude": 36.2754,
          "longitude": 126.9098,
          "saved": true
        }
      }
    ],
    "points": {
      "balance": 500,
      "cumulativeEarned": 800,
      "expirations": []
    },
    "badges": [],
    "photos": []
  }
}
```

**응답 코드:** `200` 여행 발자취 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음 · `409` 정산 완료 전 조회 등 허용되지 않은 상태 전이

---

## 3.6 Places

### GET `/places` — 주변 장소 탐색

- **Operation ID:** `getPlaces`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| query | `category` | 선택 | PlaceCategory | - |
| query | `latitude` | 선택 | number (double) | 최솟값 -90; 최댓값 90 |
| query | `longitude` | 선택 | number (double) | 최솟값 -180; 최댓값 180 |
| query | `cursor` | 선택 | string | - |
| query | `size` | 선택 | integer | 최솟값 1; 최댓값 100; 기본값 20 |

#### 요청 예시

```javascript
GET /v1/places?category=HERITAGE&latitude=36.2754&longitude=126.9098&size=1 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 장소 목록

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "placeId": "550e8400-e29b-41d4-a716-446655440000",
        "category": "HERITAGE",
        "name": "부소산성",
        "description": "부소산성은 백제의 마지막 도성을 지킨 산성일까요?",
        "latitude": 36.2754,
        "longitude": 126.9098,
        "saved": true
      }
    ],
    "page": {
      "nextCursor": "next-cursor",
      "hasNext": true
    }
  }
}
```

**응답 코드:** `200` 장소 목록 · `401` 인증 필요 또는 인증 실패

---

### GET `/places/{placeId}` — 장소 상세 조회

- **Operation ID:** `getPlace`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `placeId` | 필수 | string (uuid) | - |
| query | `latitude` | 선택 | number (double) | 최솟값 -90; 최댓값 90 |
| query | `longitude` | 선택 | number (double) | 최솟값 -180; 최댓값 180 |

#### 요청 예시

```javascript
GET /v1/places/550e8400-e29b-41d4-a716-446655440000?latitude=36.2754&longitude=126.9098 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 장소 상세

```json
{
  "success": true,
  "data": {
    "placeId": "550e8400-e29b-41d4-a716-446655440000",
    "category": "HERITAGE",
    "name": "부소산성",
    "description": "부소산성은 백제의 마지막 도성을 지킨 산성입니다 어쩌구",
    "latitude": 36.2754,
    "longitude": 126.9098,
    "saved": true
  }
}
```

**응답 코드:** `200` 장소 상세 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

### GET `/members/me/saved-places` — 저장한 장소 목록 조회

- **Operation ID:** `getSavedPlaces`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| query | `category` | 선택 | PlaceCategory | - |
| query | `latitude` | 선택 | number (double) | 최솟값 -90; 최댓값 90 |
| query | `longitude` | 선택 | number (double) | 최솟값 -180; 최댓값 180 |
| query | `cursor` | 선택 | string | - |
| query | `size` | 선택 | integer | 최솟값 1; 최댓값 100; 기본값 20 |

#### 요청 예시

```javascript
GET /v1/members/me/saved-places?category=HERITAGE&latitude=36.2754&longitude=126.9098&size=1 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 장소 목록

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "placeId": "550e8400-e29b-41d4-a716-446655440000",
        "category": "HERITAGE",
        "name": "부소산성",
        "description": "부소산성은 백제의 마지막 도성을 지킨 산성일까요?",
        "latitude": 36.2754,
        "longitude": 126.9098,
        "saved": true
      }
    ],
    "page": {
      "nextCursor": "next-cursor",
      "hasNext": true
    }
  }
}
```

**응답 코드:** `200` 장소 목록 · `401` 인증 필요 또는 인증 실패

---

### PUT `/members/me/saved-places/{placeId}` — 장소 저장

- **Operation ID:** `savePlace`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `placeId` | 필수 | string (uuid) | - |

#### 요청 예시

```javascript
PUT /v1/members/me/saved-places/550e8400-e29b-41d4-a716-446655440000 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 처리 성공

```json
{
  "success": true,
  "data": {}
}
```

**응답 코드:** `200` 처리 성공 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

### DELETE `/members/me/saved-places/{placeId}` — 저장한 장소 삭제

- **Operation ID:** `deleteSavedPlace`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `placeId` | 필수 | string (uuid) | - |

#### 요청 예시

```javascript
DELETE /v1/members/me/saved-places/550e8400-e29b-41d4-a716-446655440000 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 처리 성공

```json
{
  "success": true,
  "data": {}
}
```

**응답 코드:** `200` 처리 성공 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

## 3.7 Missions

### GET `/missions/nearby` — 주변 미션 조회

- **Operation ID:** `getNearbyMissions`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| query | `latitude` | 필수 | number (double) | 최솟값 -90; 최댓값 90 |
| query | `longitude` | 필수 | number (double) | 최솟값 -180; 최댓값 180 |
| query | `tripId` | 필수 | string (uuid) | - |

#### 요청 예시

```javascript
GET /v1/missions/nearby?latitude=36.2754&longitude=126.9098&tripId=550e8400-e29b-41d4-a716-446655440000 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 미션 목록

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "missionId": "550e8400-e29b-41d4-a716-446655440000",
        "tripId": "550e8400-e29b-41d4-a716-446655440000",
        "placeId": "550e8400-e29b-41d4-a716-446655440000",
        "type": "MULTIPLE_CHOICE",
        "title": "백제 역사 퀴즈",
        "rewardPoints": 100,
        "availability": "LOCKED",
        "radiusMeters": 100,
        "remainingAttempts": 2
      }
    ]
  }
}
```

**응답 코드:** `200` 미션 목록 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패

---

### GET `/missions/{missionId}` — 미션 상세 조회

- **Operation ID:** `getMission`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `missionId` | 필수 | string (uuid) | - |
| query | `latitude` | 필수 | number (double) | 최솟값 -90; 최댓값 90 |
| query | `longitude` | 필수 | number (double) | 최솟값 -180; 최댓값 180 |

#### 요청 예시

```javascript
GET /v1/missions/550e8400-e29b-41d4-a716-446655440000?latitude=36.2754&longitude=126.9098 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 객관식 미션

```json
{
  "success": true,
  "data": {
    "missionId": "550e8400-e29b-41d4-a716-446655440000",
    "tripId": "550e8400-e29b-41d4-a716-446655440001",
    "placeId": "550e8400-e29b-41d4-a716-446655440002",
    "type": "MULTIPLE_CHOICE",
    "title": "백제 역사 퀴즈",
    "rewardPoints": 100,
    "availability": "AVAILABLE",
    "radiusMeters": 100,
    "description": "부소산성은 백제의 마지막 도성을 지킨 산성일까요?",
    "choices": [
      {
        "choiceId": "choice-1",
        "label": "예, 맞습니다"
      },
      {
        "choiceId": "choice-2",
        "label": "아니요, 아닙니다"
      }
    ]
  }
}
```

#### 성공 응답 예시 — `200` OX 미션

```json
{
  "success": true,
  "data": {
    "missionId": "550e8400-e29b-41d4-a716-446655440010",
    "tripId": "550e8400-e29b-41d4-a716-446655440001",
    "placeId": "550e8400-e29b-41d4-a716-446655440011",
    "type": "OX",
    "title": "정림사지 오층석탑 OX 퀴즈",
    "rewardPoints": 100,
    "availability": "AVAILABLE",
    "radiusMeters": 100,
    "description": "정림사지 오층석탑은 백제 시대에 세워졌을까요?"
  }
}
```

#### 성공 응답 예시 — `200` 사진 인증 미션

```json
{
  "success": true,
  "data": {
    "missionId": "550e8400-e29b-41d4-a716-446655440020",
    "tripId": "550e8400-e29b-41d4-a716-446655440001",
    "placeId": "550e8400-e29b-41d4-a716-446655440021",
    "type": "PHOTO",
    "title": "궁남지 사진 인증",
    "rewardPoints": 150,
    "availability": "AVAILABLE",
    "radiusMeters": 100,
    "description": "궁남지를 배경으로 사진을 촬영해 주세요."
  }
}
```

**응답 코드:** `200` 미션 상세 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

### POST `/mission-photos/presigned-url` — 사진 인증 이미지 업로드 URL 발급

S3에 사진을 직접 업로드할 수 있는 Presigned PUT URL을 발급한다.
클라이언트는 API 인증 헤더나 multipart 형식 없이 응답의 uploadUrl로 파일 원본 바이트를 PUT한다.
응답의 headers는 S3 요청에 그대로 포함해야 하며, 업로드 성공 상태 코드는 200이다.
업로드 후 발급받은 photoId를 미션 제출 API에 전달한다.
- **Operation ID:** `createMissionPhotoUploadUrl`
- **인증:** Bearer JWT 필요
- **멱등성:** `Idempotency-Key` 필수

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `tripId` | string (uuid) | 필수 | - |
| `missionId` | string (uuid) | 필수 | - |
| `fileName` | string | 필수 | 길이 1~255 |
| `contentType` | string | 필수 | enum: image/jpeg, image/png, image/webp |
| `fileSizeBytes` | integer | 필수 | 최솟값 1; 서버에 설정된 최대 업로드 크기를 초과하면 413 응답을 반환한다. |

#### 요청 예시

```javascript
POST /v1/mission-photos/presigned-url HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
Content-Type: application/json

{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "missionId": "550e8400-e29b-41d4-a716-446655440000",
  "fileName": "mission-photo.jpg",
  "contentType": "image/jpeg",
  "fileSizeBytes": 1048576
}
```

#### 성공 응답 예시 — `201` 사진 업로드용 Presigned URL 발급 결과

```json
{
  "success": true,
  "data": {
    "photoId": "550e8400-e29b-41d4-a716-446655440000",
    "uploadUrl": "https://buyeoon-uploads.s3.ap-northeast-2.amazonaws.com/mission-photos/example?X-Amz-Signature=...",
    "method": "PUT",
    "headers": {
      "Content-Type": "image/jpeg"
    },
    "successStatus": 200,
    "expiresAt": "2026-08-06T15:30:00+09:00"
  }
}
```

**응답 코드:** `201` 사진 업로드용 Presigned URL 발급 결과 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음 · `409` 멱등성 키를 다른 요청에 재사용했거나 허용되지 않은 상태 전이 · `413` 업로드 파일 크기 초과

---

### POST `/missions/{missionId}/submissions` — 미션 답안 또는 사진 인증 제출

미션 유형에 맞는 답안만 전달한다. PHOTO 유형은 Presigned URL 발급 시 받은 photoId가 필수다. 서버는 S3 객체의 소유자, 실제 크기, Content-Type을 발급 요청 정보와 대조한 뒤 미션을 처리한다. 미션을 처음 완료하면 연결된 문화재의 방문 기록을 해당 여행에 한 번만 생성한다. 같은 여행에서 같은 문화재에 연결된 다른 미션을 완료해도 방문 기록은 중복 생성하지 않는다.
- **Operation ID:** `submitMission`
- **인증:** Bearer JWT 필요
- **멱등성:** `Idempotency-Key` 필수

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `missionId` | 필수 | string (uuid) | - |

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 조건 | 설명 |
| --- | --- | --- | --- |
| `tripId` | string (uuid) | 항상 필수 | 진행 중인 여행 ID |
| `type` | string | 항상 필수 | `MULTIPLE_CHOICE`, `OX`, `PHOTO` 중 하나이며 요청 스키마를 결정한다. |
| `choiceId` | string | MULTIPLE_CHOICE 필수 | 선택한 보기 ID |
| `oxAnswer` | boolean | OX 필수 | O는 true, X는 false |
| `photoId` | string (uuid) | PHOTO 필수 | Presigned URL 발급 응답에서 받은 사진 ID |
| `location` | Location | 항상 필수 | 제출 시점의 위치 |

#### 요청 예시

```javascript
POST /v1/missions/550e8400-e29b-41d4-a716-446655440000/submissions HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
Content-Type: application/json

{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "PHOTO",
  "photoId": "550e8400-e29b-41d4-a716-446655440001",
  "location": {
    "latitude": 36.2754,
    "longitude": 126.9098,
    "accuracyMeters": 8.5,
    "capturedAt": "2026-08-06T15:30:00+09:00"
  }
}
```

#### 성공 응답 예시 — `200` 미션 제출 결과

```json
{
  "success": true,
  "data": {
    "missionId": "550e8400-e29b-41d4-a716-446655440000",
    "completed": true,
    "remainingAttempts": null,
    "rewardPoints": 100,
    "pointBalance": 1200,
    "visitRecorded": true,
    "visitId": "550e8400-e29b-41d4-a716-446655440010"
  }
}
```

**응답 코드:** `200` 미션 제출 결과 · `400` 잘못된 요청 · `401` 인증 필요 또는 인증 실패 · `403` 권한 또는 위치 조건 미충족 · `404` 대상을 찾을 수 없음 · `409` 멱등성 키를 다른 요청에 재사용했거나 허용되지 않은 상태 전이

---

## 3.8 Points

### GET `/members/me/points` — 포인트 잔액 조회

- **Operation ID:** `getMyPointBalance`
- **인증:** Bearer JWT 필요

#### 요청 예시

```javascript
GET /v1/members/me/points HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 포인트 잔액

```json
{
  "success": true,
  "data": {
    "balance": 1200,
    "cumulativeEarned": 2500,
    "expirations": [
      {
        "points": 500,
        "expiresAt": "2026-08-16T15:30:00+09:00"
      },
      {
        "points": 300,
        "expiresAt": "2026-08-20T10:00:00+09:00"
      }
    ]
  }
}
```

**응답 코드:** `200` 포인트 잔액 · `401` 인증 필요 또는 인증 실패

---

### GET `/members/me/point-transactions` — 포인트 내역 조회

- **Operation ID:** `getMyPointTransactions`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| query | `cursor` | 선택 | string | - |
| query | `size` | 선택 | integer | 최솟값 1; 최댓값 100; 기본값 20 |

#### 요청 예시

```javascript
GET /v1/members/me/point-transactions?size=1 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 포인트 내역

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "transactionId": "550e8400-e29b-41d4-a716-446655440000",
        "type": "EARN",
        "amount": 100,
        "balanceAfter": 1200,
        "description": "부소산성은 백제의 마지막 도성을 지킨 산성일까요?",
        "occurredAt": "2026-08-06T15:30:00+09:00"
      }
    ],
    "page": {
      "nextCursor": "next-cursor",
      "hasNext": true
    }
  }
}
```

**응답 코드:** `200` 포인트 내역 · `401` 인증 필요 또는 인증 실패

---

## 3.9 Badges

배지는 하나 이상의 메트릭 조건을 가지며 모든 조건을 충족해야 획득한다. 진행도와 상태는 원본 활동 데이터 및 배지 획득 이력으로 계산한다.

### GET `/members/me/badges` — 배지 진척도 목록 조회

- **Operation ID:** `getMyBadges`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| query | `category` | 선택 | BadgeCategory | - |

#### 요청 예시

```javascript
GET /v1/members/me/badges?category=EXPLORATION HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 배지 목록

```json
{
  "success": true,
  "data": {
    "earnedCount": 0,
    "totalCount": 1,
    "items": [
      {
        "badgeId": "550e8400-e29b-41d4-a716-446655440000",
        "category": "EXPLORATION",
        "name": "부소산성 탐험가",
        "description": "부소산성 문화재 퀴즈를 완료한다.",
        "imageUrl": "https://cdn.buyeoon.example.com/example.png",
        "condition": "부소산성 방문 기록 1회",
        "conditions": [
          {
            "metricKey": "HERITAGE_VISITED_COUNT",
            "progress": 0,
            "threshold": 1,
            "achieved": false
          }
        ],
        "status": "NOT_EARNED",
        "earnedAt": null
      }
    ]
  }
}
```

**응답 코드:** `200` 배지 목록 · `401` 인증 필요 또는 인증 실패

---

### GET `/members/me/badges/{badgeId}` — 배지 상세 조회

- **Operation ID:** `getMyBadge`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `badgeId` | 필수 | string (uuid) | - |

#### 요청 예시

```javascript
GET /v1/members/me/badges/550e8400-e29b-41d4-a716-446655440000 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 배지 상세

```json
{
  "success": true,
  "data": {
    "badgeId": "550e8400-e29b-41d4-a716-446655440000",
    "category": "EXPLORATION",
    "name": "부소산성 탐험가",
    "description": "부소산성 문화재 퀴즈를 완료한다.",
    "imageUrl": "https://cdn.buyeoon.example.com/example.png",
    "condition": "부소산성 방문 기록 1회",
    "conditions": [
      {
        "metricKey": "HERITAGE_VISITED_COUNT",
        "progress": 1,
        "threshold": 1,
        "achieved": true
      }
    ],
    "status": "EARNED",
    "earnedAt": "2026-08-06T11:20:00+09:00"
  }
}
```

**응답 코드:** `200` 배지 상세 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

## 3.10 Notifications

### GET `/members/me/notifications` — 알림 목록 조회

- **Operation ID:** `getMyNotifications`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| query | `cursor` | 선택 | string | - |
| query | `size` | 선택 | integer | 최솟값 1; 최댓값 100; 기본값 20 |

#### 요청 예시

```javascript
GET /v1/members/me/notifications?size=1 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
```

#### 성공 응답 예시 — `200` 알림 목록

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "notificationId": "550e8400-e29b-41d4-a716-446655440000",
        "type": "POINT",
        "title": "백제 역사 퀴즈",
        "body": "새로운 알림이 도착했습니다.",
        "read": true,
        "occurredAt": "2026-08-06T15:30:00+09:00",
        "targetType": "MISSION",
        "targetId": "550e8400-e29b-41d4-a716-446655440000"
      }
    ],
    "page": {
      "nextCursor": "next-cursor",
      "hasNext": true
    }
  }
}
```

**응답 코드:** `200` 알림 목록 · `401` 인증 필요 또는 인증 실패

---

### PATCH `/members/me/notifications/{notificationId}` — 알림 읽음 처리

- **Operation ID:** `readNotification`
- **인증:** Bearer JWT 필요

#### 경로·쿼리 파라미터

| 위치 | 이름 | 필수 | 타입 | 제약 |
| --- | --- | --- | --- | --- |
| path | `notificationId` | 필수 | string (uuid) | - |

#### 요청 본문 (application/json)

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `read` | boolean | 필수 | 고정값: True |

#### 요청 예시

```javascript
PATCH /v1/members/me/notifications/550e8400-e29b-41d4-a716-446655440000 HTTP/1.1
Host: api.buyeoon.example.com
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "read": true
}
```

#### 성공 응답 예시 — `200` 알림 정보

```json
{
  "success": true,
  "data": {
    "notificationId": "550e8400-e29b-41d4-a716-446655440000",
    "type": "POINT",
    "title": "백제 역사 퀴즈",
    "body": "새로운 알림이 도착했습니다.",
    "read": true,
    "occurredAt": "2026-08-06T15:30:00+09:00",
    "targetType": "MISSION",
    "targetId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

**응답 코드:** `200` 알림 정보 · `401` 인증 필요 또는 인증 실패 · `404` 대상을 찾을 수 없음

---

## 4. 데이터 모델

엔드포인트에서 참조하는 주요 스키마와 enum 정의다.

### `ErrorData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `code` | string | 필수 | - |
| `message` | string | 필수 | - |
| `details` | object | 선택 | - |

### `PageInfo`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `nextCursor` | string 또는 null | 선택 | - |
| `hasNext` | boolean | 필수 | - |

### `Location`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `latitude` | number (double) | 필수 | 최솟값 -90; 최댓값 90 |
| `longitude` | number (double) | 필수 | 최솟값 -180; 최댓값 180 |
| `accuracyMeters` | number 또는 null | 선택 | 최솟값 0 |
| `capturedAt` | string (date-time) | 필수 | - |

### `SocialProvider`

허용값: `KAKAO` · `APPLE`

### `SocialLoginRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `provider` | SocialProvider | 필수 | - |
| `authorizationCode` | string | 필수 | 길이 1~∞ |

### `SocialAccountLinkRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `provider` | SocialProvider | 필수 | 연결할 소셜 로그인 제공자 |
| `authorizationCode` | string | 필수 | 길이 1~∞ |

### `RefreshTokenRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `refreshToken` | string | 필수 | - |

### `AuthData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `accessToken` | string | 필수 | - |
| `refreshToken` | string | 필수 | - |
| `expiresInSeconds` | integer | 필수 | 최솟값 1 |
| `isNewMember` | boolean | 필수 | - |
| `member` | Member | 필수 | - |

### `MemberStatus`

허용값: `ACTIVE` · `WITHDRAWN`

### `Member`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `memberId` | string (uuid) | 필수 | - |
| `status` | MemberStatus | 필수 | - |
| `provider` | SocialProvider | 필수 | - |
| `displayName` | string 또는 null | 선택 | 길이 0~8 |
| `characterId` | string 또는 null | 선택 | - |
| `requiredTermsAgreed` | boolean | 필수 | - |
| `citizenCardIssued` | boolean | 필수 | - |
| `createdAt` | string (date-time) | 필수 | - |

### `ProfileUpdateRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `displayName` | string | 선택 | 길이 1~8 |
| `characterId` | string (uuid) | 선택 | - |

### `Settings`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `nearbyQuizNotificationEnabled` | boolean | 필수 | - |
| `darkModeEnabled` | boolean | 필수 | - |
| `version` | integer | 필수 | 최솟값 0 |

### `SettingsUpdateRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `nearbyQuizNotificationEnabled` | boolean | 선택 | - |
| `darkModeEnabled` | boolean | 선택 | - |
| `deviceNotificationPermissionGranted` | boolean | 선택 | - |
| `deviceLocationPermissionGranted` | boolean | 선택 | - |
| `version` | integer | 필수 | 최솟값 0 |

### `TermType`

허용값: `SERVICE` · `PRIVACY` · `MARKETING`

### `Term`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `termId` | string (uuid) | 필수 | - |
| `type` | TermType | 필수 | - |
| `version` | string | 필수 | - |
| `required` | boolean | 필수 | - |
| `title` | string | 필수 | - |
| `content` | string | 필수 | - |
| `effectiveAt` | string (date-time) | 필수 | - |

### `TermConsentRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `consents` | object[] | 필수 | 최소 1개 |

### `TermConsentItem`

약관 한 건에 대한 동의 요청이다.

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `termId` | string (uuid) | 필수 | 동의 대상 약관 ID |
| `version` | string | 필수 | 사용자가 확인한 약관 버전 |
| `agreed` | boolean | 필수 | 해당 약관에 대한 동의 여부 |

### `TermConsentData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `requiredTermsAgreed` | boolean | 필수 | - |
| `agreedAt` | string (date-time) | 필수 | - |

### `CardOption`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `id` | string (uuid) | 필수 | - |
| `name` | string | 필수 | - |
| `imageUrl` | string (uri) | 필수 | - |

### `CitizenCardCreateRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `displayName` | string | 필수 | 길이 1~8 |
| `characterId` | string (uuid) | 필수 | - |
| `themeId` | string (uuid) | 필수 | - |
| `location` | Location | 필수 | - |

### `CitizenCard`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `cardId` | string (uuid) | 필수 | - |
| `displayName` | string | 필수 | 길이 0~8 |
| `character` | CardOption | 필수 | - |
| `theme` | CardOption | 필수 | - |
| `issuedAt` | string (date-time) | 필수 | - |

### `BarcodeData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `citizenCard` | CitizenCard | 필수 | - |
| `barcodeValue` | string | 필수 | - |
| `pointBalance` | integer | 필수 | 최솟값 0 |
| `simulationOnly` | boolean | 필수 | 고정값: True |
| `notice` | string | 선택 | - |

### `TripStatus`

허용값: `IN_PROGRESS` · `ENDED` · `SETTLED`

### `TripStartRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `location` | Location | 필수 | - |

### `Trip`

| 필드 | 타입 | 필수 |
| --- | --- | --- |
| `tripId` | string (uuid) | 필수 |
| `status` | TripStatus | 필수 |
| `startedAt` | string (date-time) | 필수 |
| `endedAt` | string 또는 null | 선택 |
| `settledAt` | string 또는 null | 선택 |

### `SettlementChoice`

허용값: `LEAVE_TO_BUYEO` · `CARRY_OVER`

### `TripSettlementRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `choice` | SettlementChoice | 필수 | - |

### `TripSettlementData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `tripId` | string (uuid) | 필수 | - |
| `choice` | SettlementChoice | 필수 | - |
| `settledPoints` | integer | 필수 | 최솟값 0 |
| `remainingBalance` | integer | 필수 | 최솟값 0 |
| `expiresAt` | string 또는 null | 선택 | CARRY_OVER 선택 시 정산 완료 시각부터 10×24시간 뒤 |
| `settledAt` | string (date-time) | 필수 | - |

### `PlaceCategory`

허용값: `HERITAGE` · `RESTAURANT` · `CAFE`

### `Place`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `placeId` | string (uuid) | 필수 | - |
| `category` | PlaceCategory | 필수 | - |
| `name` | string | 필수 | - |
| `summary` | string 또는 null | 선택 | - |
| `description` | string 또는 null | 선택 | - |
| `address` | string 또는 null | 선택 | - |
| `imageUrl` | string 또는 null | 선택 | - |
| `latitude` | number (double) | 필수 | - |
| `longitude` | number (double) | 필수 | - |
| `distanceMeters` | integer 또는 null | 선택 | 최솟값 0 |
| `walkingMinutes` | integer 또는 null | 선택 | 최솟값 0 |
| `saved` | boolean | 필수 | - |

### `MissionType`

허용값: `MULTIPLE_CHOICE` · `OX` · `PHOTO`

### `MissionAvailability`

허용값: `LOCKED` · `AVAILABLE` · `EXHAUSTED` · `COMPLETED`

### `MissionChoice`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `choiceId` | string | 필수 | - |
| `label` | string | 필수 | - |

### `Mission`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `missionId` | string (uuid) | 필수 | - |
| `tripId` | string (uuid) | 필수 | - |
| `placeId` | string (uuid) | 필수 | - |
| `type` | MissionType | 필수 | - |
| `title` | string | 필수 | - |
| `rewardPoints` | integer | 필수 | 최솟값 0 |
| `availability` | MissionAvailability | 필수 | - |
| `radiusMeters` | integer | 필수 | 고정값: 100 |
| `remainingAttempts` | integer 또는 null | 선택 | 최솟값 0 |

### `MissionDetail`

`type`을 discriminator로 사용하는 상세 응답 union이다.

| type | 스키마 | 필수 상세 필드 |
| --- | --- | --- |
| `MULTIPLE_CHOICE` | `MultipleChoiceMissionDetail` | `description`, `choices`(최소 2개) |
| `OX` | `OxMissionDetail` | `description` |
| `PHOTO` | `PhotoMissionDetail` | `description` |

모든 상세 유형은 목록용 `Mission` 필드에 비어 있지 않은 `description`을 추가한다.

### `MissionPhotoUploadUrlRequest`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `tripId` | string (uuid) | 필수 | - |
| `missionId` | string (uuid) | 필수 | - |
| `fileName` | string | 필수 | 길이 1~255 |
| `contentType` | string | 필수 | enum: image/jpeg, image/png, image/webp |
| `fileSizeBytes` | integer | 필수 | 최솟값 1; 서버에 설정된 최대 업로드 크기를 초과하면 413 응답을 반환한다. |

### `MissionPhotoUploadUrlData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `photoId` | string (uuid) | 필수 | - |
| `uploadUrl` | string (uri) | 필수 | S3 Presigned PUT URL |
| `method` | string | 필수 | 고정값: PUT |
| `headers` | object | 필수 | S3 업로드 요청에 그대로 포함해야 하는 헤더 |
| `successStatus` | integer | 필수 | 고정값: 200 |
| `expiresAt` | string (date-time) | 필수 | - |

### `MissionPhotoData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `photoId` | string (uuid) | 필수 | - |
| `url` | string (uri) | 필수 | - |
| `uploadedAt` | string (date-time) | 필수 | - |

### `MissionSubmissionRequest`

`type`을 discriminator로 사용하는 제출 요청 union이다. 유형과 관계없는 답안 필드는 전송할 수 없다.

| type | 스키마 | 유형별 필수 답안 |
| --- | --- | --- |
| `MULTIPLE_CHOICE` | `MultipleChoiceMissionSubmissionRequest` | `choiceId` |
| `OX` | `OxMissionSubmissionRequest` | `oxAnswer` |
| `PHOTO` | `PhotoMissionSubmissionRequest` | `photoId` |

세 유형 모두 `tripId`, `type`, `location`이 필수다.

### `MissionSubmissionData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `missionId` | string (uuid) | 필수 | - |
| `completed` | boolean | 필수 | - |
| `remainingAttempts` | integer 또는 null | 선택 | 최솟값 0 |
| `rewardPoints` | integer | 필수 | 최솟값 0 |
| `pointBalance` | integer | 필수 | 최솟값 0 |
| `visitRecorded` | boolean | 필수 | 이번 완료 처리로 문화재 방문 기록을 새로 생성했는지 여부 |
| `visitId` | string 또는 null (uuid) | 선택 | 새 방문 기록 ID. 이미 같은 여행에 방문 기록이 있으면 null |

### `PointExpiration`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `points` | integer | 필수 | 최솟값 1 |
| `expiresAt` | string (date-time) | 필수 | - |

### `PointSummaryData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `balance` | integer | 필수 | 최솟값 0 |
| `cumulativeEarned` | integer | 필수 | 최솟값 0 |
| `expirations` | PointExpiration[] | 필수 | 만료 시각이 빠른 순서로 정렬하며, 만료 예정 포인트가 없으면 빈 배열 |

### `PointTransactionType`

허용값: `EARN` · `LEAVE_TO_BUYEO` · `EXPIRE` · `ADJUST`

### `PointTransaction`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `transactionId` | string (uuid) | 필수 | - |
| `type` | PointTransactionType | 필수 | - |
| `amount` | integer | 필수 | - |
| `balanceAfter` | integer | 필수 | 최솟값 0 |
| `description` | string | 필수 | - |
| `occurredAt` | string (date-time) | 필수 | - |

### `BadgeCategory`

허용값: `EXPLORATION` · `QUIZ` · `RECORD` · `ASSET` · `SPECIAL`

### `BadgeStatus`

허용값: `NOT_EARNED` · `IN_PROGRESS` · `EARNED`

### `BadgeMetric`

허용값: `MISSION_COMPLETED_COUNT` · `HERITAGE_VISITED_COUNT` · `POINT_DONATION_COUNT`

### `BadgeCondition`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `metricKey` | BadgeMetric | 필수 | Spring이 계산하는 메트릭 |
| `progress` | integer | 필수 | 최솟값 0 |
| `threshold` | integer | 필수 | 최솟값 1 |
| `achieved` | boolean | 필수 | 현재 조건 충족 여부 |

### `Badge`

| 필드 | 타입 | 필수 |
| --- | --- | --- |
| `badgeId` | string (uuid) | 필수 |
| `category` | BadgeCategory | 필수 |
| `name` | string | 필수 |
| `description` | string | 필수 |
| `imageUrl` | string 또는 null | 선택 |
| `condition` | string | 필수 |
| `conditions` | BadgeCondition[] | 필수 |
| `status` | BadgeStatus | 필수 |
| `earnedAt` | string 또는 null | 선택 |

### `NotificationType`

허용값: `POINT` · `BADGE` · `NEARBY_QUIZ` · `DISCOUNT` · `CITIZEN_CARD` · `BUYEO_NEWS`

### `Notification`

| 필드 | 타입 | 필수 |
| --- | --- | --- |
| `notificationId` | string (uuid) | 필수 |
| `type` | NotificationType | 필수 |
| `title` | string | 필수 |
| `body` | string | 필수 |
| `read` | boolean | 필수 |
| `occurredAt` | string (date-time) | 필수 |
| `targetType` | string 또는 null | 선택 |
| `targetId` | string 또는 null | 선택 |

### `TravelStatisticsData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `tripId` | string (uuid) | 필수 | - |
| `distanceKm` | number 또는 null | 필수 | 최솟값 0 |
| `visitedPlaceCount` | integer | 필수 | 최솟값 0 |
| `durationMinutes` | integer | 필수 | 최솟값 0 |
| `caloriesKcal` | integer 또는 null | 필수 | 최솟값 0 |

### `VisitRecord`

완료한 미션과 연결된 문화재 방문 기록이다. 같은 여행과 문화재 조합에는 한 건만 생성한다.

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `visitId` | string (uuid) | 필수 | 방문 기록 ID |
| `missionId` | string (uuid) | 필수 | 방문 기록 생성을 유발한 완료 미션 ID |
| `place` | Place | 필수 | 미션과 연결된 문화재 |
| `visitedAt` | string (date-time) | 필수 | 미션을 처음 완료해 방문이 확정된 시각 |

### `FootprintData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `trip` | Trip | 필수 | - |
| `statistics` | TravelStatisticsData | 필수 | - |
| `visits` | VisitRecord[] | 필수 | - |
| `points` | PointSummaryData | 필수 | - |
| `badges` | Badge[] | 필수 | - |
| `photos` | MissionPhotoData[] | 필수 | - |

### `TermListData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `items` | Term[] | 필수 | - |

### `CitizenCardOptionsData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `characters` | CardOption[] | 필수 | - |
| `themes` | CardOption[] | 필수 | - |

### `PlaceListData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `items` | Place[] | 필수 | - |
| `page` | PageInfo | 필수 | - |

### `MissionListData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `items` | Mission[] | 필수 | - |

### `PointTransactionListData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `items` | PointTransaction[] | 필수 | - |
| `page` | PageInfo | 필수 | - |

### `BadgeListData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `earnedCount` | integer | 필수 | 최솟값 0 |
| `totalCount` | integer | 필수 | 최솟값 0 |
| `items` | Badge[] | 필수 | - |

### `NotificationListData`

| 필드 | 타입 | 필수 | 제약·설명 |
| --- | --- | --- | --- |
| `items` | Notification[] | 필수 | - |
| `page` | PageInfo | 필수 | - |

## 5. 주요 enum 빠른 참조

| 구분 | 허용값 |
| --- | --- |
| `SocialProvider` | `KAKAO` · `APPLE` |
| `MemberStatus` | `ACTIVE` · `WITHDRAWN` |
| `TermType` | `SERVICE` · `PRIVACY` · `MARKETING` |
| `TripStatus` | `IN_PROGRESS` · `ENDED` · `SETTLED` |
| `SettlementChoice` | `LEAVE_TO_BUYEO` · `CARRY_OVER` |
| `PlaceCategory` | `HERITAGE` · `RESTAURANT` · `CAFE` |
| `MissionType` | `MULTIPLE_CHOICE` · `OX` · `PHOTO` |
| `MissionAvailability` | `LOCKED` · `AVAILABLE` · `EXHAUSTED` · `COMPLETED` |
| `PointTransactionType` | `EARN` · `LEAVE_TO_BUYEO` · `EXPIRE` · `ADJUST` |
| `BadgeCategory` | `EXPLORATION` · `QUIZ` · `RECORD` · `ASSET` · `SPECIAL` |
| `BadgeStatus` | `NOT_EARNED` · `IN_PROGRESS` · `EARNED` |
| `BadgeMetric` | `MISSION_COMPLETED_COUNT` · `HERITAGE_VISITED_COUNT` · `POINT_DONATION_COUNT` |
| `NotificationType` | `POINT` · `BADGE` · `NEARBY_QUIZ` · `DISCOUNT` · `CITIZEN_CARD` · `BUYEO_NEWS` |

---

*이 문서는 **`openapi.yaml`**(부여ON API 1.0.0)을 기준으로 생성했다.*
