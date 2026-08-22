---
id: UC-14
title: 배지 획득
owner: badge
participants: [mission, trip, point, notification]
policies: [authorization, idempotency, date-time, concurrency, transactions]
api: ["POST /missions/{missionId}/submissions", "PUT /trips/{tripId}/settlement"]
adrs: [ADR-003, ADR-004, ADR-010]
---

# 배지 획득

## 목적

사용자가 탐험 활동으로 배지를 획득한다.

## 사전 조건

- 로그인한 활성 회원이 배지 판정을 유발하는 활동을 확정한다.
- 지급이 중단되지 않은 배지의 모든 조건을 충족한다.

## 기본 흐름

1. 사용자가 여행 중 미션을 완료하거나 여행에서 적립한 양수 포인트를 부여에 남긴다.
2. 활동을 처리하는 application service가 변경된 metric과 함께 badge의 공개 application service를 동기 호출한다.
3. 시스템은 같은 transaction에서 방금 확정할 source 변경까지 반영한 회원의 전체 여행 활동을 기준으로 아직 획득하지 않은 배지의 모든 조건을 판정한다.
4. 조건을 처음 충족한 배지를 활동의 여행과 연결하고 활동 확정 시각을 획득 시각으로 기록한다.
5. 시스템은 배지마다 `BADGE` 알림을 생성하고, activity response의 `newlyAwardedBadges`에 배지 ID, 이름, 이미지 URL, 표시 조건과 획득 시각을 담는다.
6. 앱은 새로 획득한 배지를 배지 ID 오름차순으로 표시한다.

## Metric 판정

- `MISSION_COMPLETED_COUNT`는 모든 여행에서 완료한 mission participation 수다. 다른 여행에서 같은 mission을 다시 완료하면 다시 센다.
- `HERITAGE_VISITED_COUNT`는 모든 여행에서 방문한 고유 문화재 수다.
- `POINT_DONATION_COUNT`는 `LEAVE_TO_BUYEO`를 선택하고 `settled_points > 0`인 여행 정산 건수다.
- `PHOTO_SUBMISSION_COUNT`는 모든 여행에서 제출한 `PHOTO` 유형 mission submission 수다.
- 미션 완료는 `MISSION_COMPLETED_COUNT`를 판정하고, 같은 처리에서 방문 기록을 새로 만들었으면 `HERITAGE_VISITED_COUNT`도 한 번에 판정한다. 제출한 미션이 `PHOTO` 유형이면 `PHOTO_SUBMISSION_COUNT`도 함께 판정한다.
- 양수 포인트를 부여에 남긴 정산은 `POINT_DONATION_COUNT`를 판정한다.
- 한 활동이 여러 배지 조건을 충족하면 모두 지급하고 response와 알림 생성 순서는 배지 ID 오름차순으로 고정한다.

## Reconciliation

1. 매일 `03:00 Asia/Seoul`에 활성 회원의 미획득 배지를 원본 활동 데이터로 다시 판정한다. 회원별 transaction에서 member row를 lock한 뒤 상태를 다시 확인하고 `ACTIVE`가 아니면 건너뛴다.
2. 과거 활동으로 조건을 이미 충족한 배지는 reconciliation 확정 시각에 지급한다.
3. 이때 모든 조건에 기여한 활동 중 가장 최근 활동의 여행을 획득 배지에 연결한다.
4. 실시간 지급과 같은 `BADGE` 알림을 배지마다 생성한다.

## 예외 흐름

- 이미 획득한 배지면 response에 다시 포함하거나 알림을 다시 만들지 않고 다른 여행에도 다시 연결하지 않는다.
- 같은 회원과 배지의 판정이 동시에 실행돼도 획득 이력과 알림은 한 번만 생성한다.
- activity, 배지 획득 이력 또는 배지 알림 중 하나라도 저장하지 못하면 모두 rollback하고 요청을 다시 시도할 수 있게 한다.
- 지급이 중단된 배지는 실시간 판정과 reconciliation에서 새로 지급하지 않는다.
- 새로 획득한 배지가 없으면 `newlyAwardedBadges`는 빈 배열이다.
- 같은 idempotency key의 replay는 최초 지급 결과를 재사용하되 만료되는 배지 이미지 URL은 새로 생성한다.
- startup 시 지급 가능한 배지에 조건이 없거나 지원 Provider가 없는 metric이 발견되면 사용자 요청을 받기 전에 애플리케이션 시작을 실패시킨다.

## 선행 의존성

- Mission completion과 visit 기반 지급 slice는 UC-09(#102)가 완료되어 blocker가 없다.
- Point donation 기반 지급과 `settleTripPoints.newlyAwardedBadges` 연결 slice는 UC-24 구현에 blocked된다. UC-24 없이 이 slice를 구현하지 않는다.
- 승인된 badge 이름, 설명, 표시 조건, image key, metric과 threshold를 담은 초기 catalog seed를 별도 slice로 제공해야 한다. Catalog 승인이 끝나기 전까지 이 slice는 blocked되며 UC-14의 end-to-end acceptance와 release를 완료하지 않는다.

## 범위 제외

- 운영 환경의 초기 badge catalog와 이미지 확정·seed
- FCM push 발송
- 배지 진척도 목록과 상세 조회
