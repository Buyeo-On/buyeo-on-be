---
id: UC-10
title: 알림 조회
owner: notification
participants: []
policies: [authorization, date-time]
api: ["GET /members/me/notifications", "PATCH /members/me/notifications/{notificationId}"]
adrs: []
---

# 알림 조회

## 목적

사용자가 부여 여행 중 발생한 알림을 확인한다.

## 사전 조건

- 사용자가 로그인 상태다.

## 기본 흐름

1. 사용자가 탐험 화면에서 알림 버튼을 누른다.
2. 앱이 최근 알림과 지난 알림을 표시한다.
3. 사용자가 알림의 종류, 내용, 발생 시각을 확인한다.

## 예외 흐름

- 알림이 없으면 알림이 없는 상태를 표시한다.
- 조회에 실패하면 네트워크 오류를 안내한다.
