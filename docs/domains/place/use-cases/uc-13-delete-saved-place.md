---
id: UC-13
title: 저장한 장소 삭제
owner: place
participants: []
policies: [authorization, deletion, concurrency, transactions]
api: ["GET /places/{placeId}", "DELETE /members/me/saved-places/{placeId}"]
adrs: []
---

# 저장한 장소 삭제

## 목적

사용자가 더 이상 필요하지 않은 장소를 저장 목록에서 삭제한다.

## 사전 조건

- 사용자가 로그인 상태다.
- 해당 장소가 저장 목록에 있다.

## 기본 흐름

1. 사용자가 저장한 장소의 상세 정보를 연다.
2. 사용자가 `저장 목록에서 삭제`를 누른다.
3. 앱이 장소를 목록에서 제거하고 완료 메시지를 표시한다.

## 예외 흐름

- 이미 삭제된 장소면 최신 저장 목록을 표시한다.
- 삭제에 실패하면 네트워크 오류를 안내한다.
