# 전역 정책

다음 정책은 여러 도메인에 공통으로 적용한다. 도메인 규칙과 충돌할 경우 전역 정책을 우선한다.

- [중복 요청과 멱등성](./idempotency.md)
- [날짜와 시간대](./date-time.md)
- [삭제 정책](./deletion.md)
- [위치 인증](./location-verification.md)
- [사용자 권한](./authorization.md)
- [동시 요청](./concurrency.md)
- [트랜잭션과 실패 시 롤백](./transactions.md)

유즈케이스는 실제로 적용되는 정책만 frontmatter의 `policies`에 명시한다.
