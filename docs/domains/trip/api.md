# 여행 API

API 계약의 원본은 [`docs/raw/openapi.yaml`](../../raw/openapi.yaml)이다. 이 문서는 여행 도메인이 소유하는 operation을 찾기 위한 인덱스다.

| Operation ID | Method | Path |
| --- | --- | --- |
| `startTrip` | POST | `/trips` |
| `getCurrentTrip` | GET | `/trips/current` |
| `endTrip` | POST | `/trips/{tripId}/end` |
| `getTripStatistics` | GET | `/trips/{tripId}/statistics` |
| `getTripFootprint` | GET | `/trips/{tripId}/footprint` |

포인트 정산 endpoint는 여행 URL 아래에 있지만 포인트 상태를 변경하므로 [포인트 API](../point/api.md)가 소유한다.
