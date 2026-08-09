---
id: UC-08
title: 가볼 곳 저장
owner: place
participants: []
policies: [authorization, idempotency, concurrency, transactions]
api: ["GET /places/{placeId}", "PUT /members/me/saved-places/{placeId}"]
adrs: []
---

# 가볼 곳 저장

## 목적

사용자가 조회한 장소 또는 문화재를 저장한다.

## 사전 조건

- 사용자가 로그인 상태다.
- 사용자가 장소 상세 정보를 조회한 상태다.

## 기본 흐름

1. 사용자가 장소 또는 문화재의 상세 정보를 확인한다.
2. 사용자가 `저장하기`를 누른다.
3. 앱이 장소를 저장하고 완료 상태를 표시한다.

## 예외 흐름

- 이미 저장한 장소면 저장된 상태를 유지한다.
- 저장에 실패하면 네트워크 오류를 안내한다.
