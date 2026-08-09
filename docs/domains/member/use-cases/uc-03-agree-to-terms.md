---
id: UC-03
title: 약관 동의
owner: member
participants: []
policies: [authorization, location-verification, transactions]
api: ["GET /terms", "PUT /members/me/term-consents"]
adrs: []
---

# 약관 동의

## 목적

사용자는 필수 및 선택 약관에 동의한다.

## 사전 조건

- 사용자는 필수 약관 중 동의하지 않은 항목이 있다.

## 기본 흐름

1. 사용자는 약관 항목과 세부사항을 읽는다.
2. 사용자는 원하는 항목에 동의한다.
3. 필수 항목에 모두 동의하면 `동의하고 계속하기`를 누른다.
4. 앱이 약관 동의를 저장한다.
5. 동의가 끝나면 현재 위치를 확인하고 디지털 군민증 안내 화면으로 이동한다.

## 예외 흐름

- 필수 항목에 동의하지 않으면 계속하기 버튼을 활성화하지 않는다.
- 처리에 실패하면 네트워크 오류를 안내한다.
