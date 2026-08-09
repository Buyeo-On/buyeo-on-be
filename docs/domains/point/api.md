# 포인트 API

API 계약의 원본은 [`docs/raw/openapi.yaml`](../../raw/openapi.yaml)이다. 이 문서는 포인트 도메인이 소유하는 operation을 찾기 위한 인덱스다.

| Operation ID | Method | Path |
| --- | --- | --- |
| `getMyPointBalance` | GET | `/members/me/points` |
| `getMyPointTransactions` | GET | `/members/me/point-transactions` |
| `settleTripPoints` | PUT | `/trips/{tripId}/settlement` |
