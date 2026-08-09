---
id: UC-14
title: 배지 획득
owner: badge
participants: [mission, trip, point, notification]
policies: [idempotency, date-time, concurrency, transactions]
api: []
adrs: [ADR-003]
---

# 배지 획득

## 목적

사용자가 탐험 활동으로 배지를 획득한다.

## 사전 조건

- 사용자가 로그인 상태다.
- 사용자가 배지의 달성 조건을 충족했다.

## 기본 흐름

1. 사용자가 여행 중 배지 조건을 달성한다.
2. 시스템이 새로 획득한 배지와 해당 여행을 기록한다.
3. 앱이 배지 아이콘, 이름, 달성 조건을 표시한다.

## 예외 흐름

- 이미 획득한 배지면 중복으로 지급하거나 다른 여행에 다시 연결하지 않는다.
