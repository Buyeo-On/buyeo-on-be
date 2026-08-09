# 회원 API

API 계약의 원본은 [`docs/raw/openapi.yaml`](../../raw/openapi.yaml)이다. 이 문서는 회원 도메인이 소유하는 operation을 찾기 위한 인덱스다.

| Operation ID | Method | Path |
| --- | --- | --- |
| `socialLogin` | POST | `/auth/social-login` |
| `refreshToken` | POST | `/auth/refresh` |
| `logout` | POST | `/auth/logout` |
| `getMyMember` | GET | `/members/me` |
| `withdrawMember` | DELETE | `/members/me` |
| `linkSocialAccount` | POST | `/members/me/social-accounts` |
| `updateMyProfile` | PATCH | `/members/me/profile` |
| `getMySettings` | GET | `/members/me/settings` |
| `updateMySettings` | PATCH | `/members/me/settings` |
| `getTerms` | GET | `/terms` |
| `updateTermConsents` | PUT | `/members/me/term-consents` |
| `getCitizenCardOptions` | GET | `/citizen-cards/options` |
| `createCitizenCard` | POST | `/citizen-cards` |
| `getMyCitizenCard` | GET | `/citizen-cards/me` |
| `getMyCitizenCardBarcode` | GET | `/citizen-cards/me/barcode` |
| `upsertMyPushToken` | PUT | `/members/me/push-token` |
| `deleteMyPushToken` | DELETE | `/members/me/push-token` |

푸시 토큰 등록 API는 알림 기능에서 사용하지만 인증 세션과 토큰의 연결 상태를 회원 도메인이 소유한다.
