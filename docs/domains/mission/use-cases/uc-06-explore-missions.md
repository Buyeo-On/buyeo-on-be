---
id: UC-06
title: 퀴즈 탐색
owner: mission
participants: [trip]
policies: [authorization, location-verification]
api: ["GET /missions/nearby", "GET /missions/{missionId}"]
adrs: []
---

# 퀴즈 탐색

## 목적

사용자는 근처에 있는 퀴즈를 조회한다.

## 사전 조건

- 사용자가 로그인 상태다.
- 사용자가 필수 약관에 동의한 상태다.
- 진행 중인 여행 세션이 존재한다.

## 기본 흐름

1. 사용자의 위치 근방에 있는 퀴즈를 조회한다.
2. 퀴즈와 사용자의 거리가 100m 이하면 마커가 활성화된다.
3. 퀴즈와 사용자의 거리가 100m를 초과하면 마커가 회색으로 비활성화된다.

## 예외 흐름

- 네트워크 연결에 실패하면 네트워크 오류를 안내한다.
