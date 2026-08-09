---
id: UC-19
title: 서비스 설정
owner: member
participants: [notification]
policies: [authorization, concurrency, transactions]
api: ["GET /members/me/settings", "PATCH /members/me/settings"]
adrs: []
---

# 서비스 설정

## 목적

사용자가 여행 중 필요한 알림과 화면 표시 방식을 설정한다.

## 사전 조건

- 사용자가 로그인 상태다.

## 기본 흐름

1. 사용자가 `마이페이지`에 진입한다.
2. 사용자가 GPS 유적지 인근 알림 또는 다크 모드 설정을 변경한다.
3. 앱이 변경한 설정을 적용하고 현재 상태를 표시한다.

## 예외 흐름

- 기기 권한이 꺼져 있으면 권한 설정이 필요함을 안내한다.
- 설정 변경에 실패하면 기존 설정을 유지하고 오류를 안내한다.
