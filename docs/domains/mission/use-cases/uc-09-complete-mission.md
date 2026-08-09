---
id: UC-09
title: 퀴즈 풀이
owner: mission
participants: [trip, place, point, badge, notification]
policies: [authorization, location-verification, idempotency, date-time, concurrency, transactions]
api: ["GET /missions/{missionId}", "POST /mission-photos/presigned-url", "POST /missions/{missionId}/submissions"]
adrs: [ADR-003, ADR-008]
---

# 퀴즈 풀이

## 목적

사용자는 현장에서 퀴즈 또는 사진 인증 미션에 참여한다.

## 사전 조건

- 사용자가 로그인 상태다.
- 진행 중인 여행 세션이 존재한다.
- 퀴즈와 사용자의 거리가 100m 이내다.

## 기본 흐름

1. 스페셜 퀴즈인 경우 사용자가 도전 기회를 확인하고 도전한다.
2. 객관식 또는 OX 퀴즈인 경우 사용자가 문제를 읽고 답을 선택한다.
3. 앱이 제출한 답의 정답 여부를 표시한다.
4. 사진 인증 미션인 경우 사용자가 안내에 따라 사진을 촬영한다.
5. 사용자가 촬영 결과를 확인하고 제출하면 앱이 인증 완료로 처리한다.
6. 미션을 처음 완료하면 앱이 미션과 연결된 문화재의 방문 기록을 현재 여행에 생성한다.
7. 같은 여행에 해당 문화재의 방문 기록이 이미 있으면 중복 생성하지 않는다.

## 예외 흐름

- 퀴즈의 위치 조건을 충족하지 않으면 잠금 상태를 표시한다.
- 카메라 권한이 없으면 권한 허용을 안내한다.
- 스페셜 퀴즈의 도전 기회가 없으면 참여할 수 없음을 안내한다.
