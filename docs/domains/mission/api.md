# 미션 API

API 계약의 원본은 [`docs/raw/openapi.yaml`](../../raw/openapi.yaml)이다. 이 문서는 미션 도메인이 소유하는 operation을 찾기 위한 인덱스다.

| Operation ID | Method | Path |
| --- | --- | --- |
| `getNearbyMissions` | GET | `/missions/nearby` |
| `getMission` | GET | `/missions/{missionId}` |
| `createMissionPhotoUploadUrl` | POST | `/mission-photos/presigned-url` |
| `submitMission` | POST | `/missions/{missionId}/submissions` |

사진 미션의 업로드 순서와 공통 요청 규칙은 [API 공통 규약](../../api-spec.md)을 함께 확인한다.
