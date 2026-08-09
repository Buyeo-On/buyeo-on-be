# 장소 API

API 계약의 원본은 [`docs/raw/openapi.yaml`](../../raw/openapi.yaml)이다. 이 문서는 장소 도메인이 소유하는 operation을 찾기 위한 인덱스다.

| Operation ID | Method | Path |
| --- | --- | --- |
| `getPlaces` | GET | `/places` |
| `getPlace` | GET | `/places/{placeId}` |
| `getSavedPlaces` | GET | `/members/me/saved-places` |
| `savePlace` | PUT | `/members/me/saved-places/{placeId}` |
| `deleteSavedPlace` | DELETE | `/members/me/saved-places/{placeId}` |
