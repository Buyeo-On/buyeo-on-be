---
id: UC-16
title: 포인트 적립 내역 조회
owner: point
participants: []
policies: [authorization, date-time]
api: ["GET /members/me/points", "GET /members/me/point-transactions"]
adrs: []
---

# 포인트 적립 내역 조회

## 목적

사용자가 포인트를 언제, 어떤 활동으로 적립했는지 확인한다.

## 사전 조건

- 사용자가 로그인 상태다.

## 기본 흐름

1. 사용자가 `활동내역` 탭을 누른다.
2. 앱이 누적 포인트와 포인트 적립 내역을 표시한다.
3. 사용자가 활동명, 적립 시각, 적립 포인트를 확인한다.

## 예외 흐름

- 적립 내역이 없으면 내역이 없는 상태를 표시한다.
- 조회에 실패하면 네트워크 오류를 안내한다.
