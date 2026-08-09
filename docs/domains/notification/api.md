# 알림 API

API 계약의 원본은 [`docs/raw/openapi.yaml`](../../raw/openapi.yaml)이다. 이 문서는 알림 도메인이 소유하는 operation을 찾기 위한 인덱스다.

| Operation ID | Method | Path |
| --- | --- | --- |
| `getMyNotifications` | GET | `/members/me/notifications` |
| `readNotification` | PATCH | `/members/me/notifications/{notificationId}` |

근처 퀴즈 알림 동의는 회원 설정과 인증 세션의 푸시 토큰 API를 함께 사용한다. 해당 operation은 [회원 API](../member/api.md)가 소유한다.
