# 배지 API

API 계약의 원본은 [`docs/raw/openapi.yaml`](../../raw/openapi.yaml)이다. 이 문서는 배지 도메인이 소유하는 operation을 찾기 위한 인덱스다.

| Operation ID | Method | Path |
| --- | --- | --- |
| `getMyBadges` | GET | `/members/me/badges` |
| `getMyBadge` | GET | `/members/me/badges/{badgeId}` |

배지 획득은 별도 공개 endpoint가 아니라 미션 완료와 포인트 정산 application service가 badge의 공개 application service를 동기 호출해 평가한다. `submitMission`과 `settleTripPoints`는 이번 활동에서 처음 지급된 배지를 `newlyAwardedBadges`로 반환한다.
