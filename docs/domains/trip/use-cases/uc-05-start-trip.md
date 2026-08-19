---
id: UC-05
title: 여행 시작
owner: trip
participants: [member]
policies: [authorization, location-verification, idempotency, date-time, concurrency, transactions]
api: ["POST /trips"]
adrs: []
---

# 여행 시작

## 목적

사용자가 여행 세션을 시작한다.

## 사전 조건

- 사용자가 로그인 상태다.
- 사용자가 필수 약관에 동의한 상태다.
- 사용자가 디지털 군민증을 발급받았다.
- 사용자의 위치가 부여에 있다.
- 종료했지만 포인트를 정산하지 않은 여행이 없다.

## 기본 흐름

1. 사용자는 여행을 시작한다.

## 예외 흐름

- 종료했지만 포인트를 정산하지 않은 여행이 있으면 먼저 정산하도록 안내한다.
- 네트워크 연결에 실패하면 네트워크 오류를 안내한다.
