---
id: UC-23
title: 여행 끝내기
owner: trip
participants: [point]
policies: [authorization, idempotency, date-time, concurrency, transactions]
api: ["POST /trips/{tripId}/end"]
adrs: []
---

# 여행 끝내기

## 목적

사용자가 진행 중인 부여 여행을 마친다.

## 사전 조건

- 사용자가 로그인 상태다.
- 진행 중인 여행 세션이 존재한다.

## 기본 흐름

1. 사용자가 마이페이지에서 `부여 떠나기`를 누르거나 부여 지역을 벗어난다.
2. 앱이 오늘의 방문 장소와 지역 상권 기여 내용을 보여준다.
3. 사용자가 여행 끝내기를 확인한다.
4. 앱이 여행을 종료하고 남은 포인트 정산으로 이동한다.

## 예외 흐름

- 사용자가 취소하면 여행을 계속한다.
- 진행 중인 여행이 없거나 이미 종료된 여행을 다른 멱등성 키로 다시 종료하면 `409` 오류를 안내한다.
