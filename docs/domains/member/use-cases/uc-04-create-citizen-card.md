---
id: UC-04
title: 군민증 생성
owner: member
participants: []
policies: [authorization, location-verification, idempotency, date-time, transactions]
api: ["GET /citizen-cards/options", "POST /citizen-cards"]
adrs: []
---

# 군민증 생성

## 목적

사용자가 디지털 군민증을 생성한다.

## 사전 조건

- 사용자가 로그인 상태다.
- 사용자가 필수 약관에 동의한 상태다.
- 사용자의 위치가 부여에 있다.

## 기본 흐름

1. 사용자는 군민증에 표시될 이름을 지정한다.
2. 사용자는 군민증의 프로필 캐릭터를 지정한다.
3. 사용자는 군민증의 카드 테마를 지정한다.
4. 세 항목을 모두 지정한 사용자는 군민증을 만든다.

## 예외 흐름

- 지정하지 않은 항목이 있으면 해당 항목에 오류를 표시한다.
- 네트워크 연결에 실패하면 네트워크 오류를 안내한다.
