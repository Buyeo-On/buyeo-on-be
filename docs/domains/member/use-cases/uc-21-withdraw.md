---
id: UC-21
title: 탈퇴
owner: member
participants: [trip, place, mission, point, badge, notification]
policies: [authorization, idempotency, deletion, concurrency, transactions]
api: ["DELETE /members/me"]
adrs: [ADR-012]
---

# 탈퇴

## 목적

사용자가 부여ON 회원 자격을 종료한다.

## 사전 조건

- 사용자가 로그인 상태다.

## 기본 흐름

1. 사용자가 마이페이지에서 `회원탈퇴`를 누른다.
2. 앱이 탈퇴 시 삭제되는 정보와 유의사항을 안내한다.
3. 사용자가 탈퇴를 확정한다.
4. 시스템이 모든 인증 세션을 폐기하고 연결된 푸시 토큰을 발송 대상에서 제외한다.
5. 앱이 탈퇴를 완료하고 로그인 화면으로 이동한다.

## 예외 흐름

- 사용자가 취소하면 마이페이지로 돌아간다.
- 탈퇴에 실패하면 오류를 안내하고 로그인 상태를 유지한다.
