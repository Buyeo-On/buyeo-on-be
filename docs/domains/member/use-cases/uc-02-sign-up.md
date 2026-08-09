---
id: UC-02
title: 가입
owner: member
participants: []
policies: [authorization, location-verification, idempotency, date-time, transactions]
api: ["POST /auth/social-login", "GET /terms", "PUT /members/me/term-consents", "GET /citizen-cards/options", "POST /citizen-cards"]
adrs: [ADR-001, ADR-012]
---

# 가입

## 목적

신규 사용자를 등록하고 필수 온보딩을 진행한다.

## 사전 조건

- 가입되지 않은 소셜 계정으로 인증한 상태다.

## 기본 흐름

1. 시스템이 소셜 계정의 기본 정보로 신규 회원을 만든다.
2. 사용자가 필수 약관에 동의한다.
3. 시스템이 사용자의 현재 위치가 서버에 고정된 부여 행정구역 경계 안인지 확인한다.
4. 부여 안에 있으면 사용자가 디지털 군민증을 만든다.
5. 부여 밖이거나 위치를 확인할 수 없으면 위치 확인 안내를 표시하고 군민증 생성을 허용하지 않는다.
6. 군민증 발급을 완료한 사용자는 여행을 시작할 수 있다.

## 예외 흐름

- 필수 약관에 동의하지 않으면 위치 확인과 군민증 생성으로 진행하지 않는다.
- 회원 생성에 실패하면 가입을 완료하지 않고 다시 시도할 수 있게 한다.
- 네트워크 연결에 실패하면 네트워크 오류를 안내한다.
