# API 공통 규약과 도메인 인덱스

[OpenAPI 계약 원본](./raw/openapi.yaml)

> **버전 1.0.0** · OpenAPI 3.1.0 · 기준 파일 `openapi.yaml`

## 도메인별 API

- [회원 API](./domains/member/api.md)
- [여행 API](./domains/trip/api.md)
- [장소 API](./domains/place/api.md)
- [미션 API](./domains/mission/api.md)
- [포인트 API](./domains/point/api.md)
- [배지 API](./domains/badge/api.md)
- [알림 API](./domains/notification/api.md)

## 기본 규약

- **Base URL:** `https://api.buyeoon.example.com/v1`
- **데이터 형식:** 별도 표기가 없으면 `application/json`
- **인증:** 기본적으로 `Authorization: Bearer {accessToken}` 헤더 사용
- **인증 세션:** 액세스 JWT의 `sub`는 회원 ID, `sid`는 현재 인증 세션 ID이며 서버는 세션의 만료·폐기 여부를 확인
- **공개 API:** 소셜 로그인, 토큰 갱신, 현재 약관 목록 조회
- **시간:** ISO 8601 date-time
- **식별자:** UUID

모든 응답은 `success`와 `data`를 가지는 공통 객체 형식을 사용한다.

### 성공 응답

```json
{
  "success": true,
  "data": {}
}
```

### 오류 응답

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
| `409` | 멱등성 키 재사용, 허용되지 않은 상태 전이 또는 소셜 계정 연결 충돌 |
| `413` | 업로드 파일 크기 초과 |
| `502` | 소셜 제공자 등 동기 외부 시스템의 일시적 장애 |

## 멱등성 헤더

```text
Idempotency-Key: 7f5c6c12-3ee8-4aa5-8f32-682ef0ec35ad
```

세부 동작은 [중복 요청과 멱등성 정책](./policies/idempotency.md)을 따른다.

## 커서 페이지네이션

`cursor`와 `size`를 사용하며 `size`의 기본값은 20, 허용 범위는 1~100이다.

## 사진 미션 업로드

1. `POST /mission-photos/presigned-url`로 `photoId`, `uploadUrl`과 필수 헤더를 발급받는다.
2. API 인증 헤더나 multipart 형식 없이 `uploadUrl`로 파일 원본 바이트를 S3에 직접 `PUT`한다.
3. S3가 `200`을 반환하면 `POST /missions/{missionId}/submissions` 요청에 `photoId`를 넣는다.
4. 서버가 S3 객체의 소유자, 실제 크기와 Content-Type을 확인하고 미션을 처리한다.

요청·응답 필드, 제약, 오류 코드와 schema의 최종 계약은 OpenAPI 원본에서 해당 operation만 확인한다.
