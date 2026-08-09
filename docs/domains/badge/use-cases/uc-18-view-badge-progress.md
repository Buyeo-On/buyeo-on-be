---
id: UC-18
title: 배지 진척도 조회
owner: badge
participants: [mission, trip, point]
policies: [authorization]
api: ["GET /members/me/badges", "GET /members/me/badges/{badgeId}"]
adrs: [ADR-003]
---

# 배지 진척도 조회

## 목적

사용자가 부여 탐험 배지의 획득 여부와 달성 진행도를 확인한다.

## 사전 조건

- 사용자가 로그인 상태다.

## 기본 흐름

1. 사용자가 활동 내역에서 `부여 탐험 배지`의 전체보기를 누른다.
2. 앱이 분야별 배지와 전체 획득 현황을 표시한다.
3. 사용자가 각 배지의 달성 조건과 현재 진행도를 확인한다.

## 예외 흐름

- 획득한 배지가 없어도 전체 배지와 진행도를 표시한다.
- 조회에 실패하면 네트워크 오류를 안내한다.
