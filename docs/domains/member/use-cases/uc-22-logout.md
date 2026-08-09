---
id: UC-22
title: 로그아웃
owner: member
participants: [notification]
policies: [authorization, deletion, transactions]
api: ["POST /auth/logout"]
adrs: [ADR-012]
---

# 로그아웃

## 목적

사용자가 현재 기기에서 로그인 상태를 종료한다.

## 사전 조건

- 사용자가 로그인 상태다.

## 기본 흐름

1. 사용자가 마이페이지에서 `로그아웃`을 누른다.
2. 앱이 로그아웃 여부를 다시 확인한다.
3. 사용자가 로그아웃을 확정한다.
4. 시스템이 현재 인증 세션을 폐기하고 연결된 푸시 토큰을 발송 대상에서 제외한다.
5. 앱이 로그인 화면으로 이동한다.

## 예외 흐름

- 사용자가 취소하면 마이페이지로 돌아간다.
