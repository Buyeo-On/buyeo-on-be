# 배지 도메인 모델과 규칙

## 책임

배지 정의와 조건, 원본 활동 데이터 기반 진행도 계산과 회원의 획득 이력을 소유한다.

## 모델

### 배지

탐험, 퀴즈, 기록, 지역상권 활동의 달성을 나타내는 보상이다. 이름, 설명, 달성 조건과 이미지를 가진다.

### 배지 조건

Spring이 계산하는 메트릭과 달성 임계값의 조합이다. 배지는 하나 이상의 조건을 가진다.

### 배지 진척도

원본 활동 데이터에서 계산한 각 메트릭의 현재값과 조건 충족 여부다.

### 획득 배지

회원이 모든 조건을 충족해 받은 배지와 획득 시각의 이력이다. 배지를 처음 획득한 여행과 연결한다.

### 신규 획득 배지

한 활동의 처리 결과로 이번에 처음 지급된 배지다. Activity response에는 배지 ID, 이름, 이미지 URL, 표시 조건과 획득 시각을 제공한다.

## 규칙

1. 배지는 하나 이상의 메트릭 조건을 가지며 모든 조건을 충족하면 지급한다.
2. 메트릭은 회원의 전체 여행 활동을 누적하며 다음 값을 지원한다.
   - `MISSION_COMPLETED_COUNT`: 완료한 mission participation 수. 다른 여행에서 같은 mission을 다시 완료하면 다시 센다.
   - `HERITAGE_VISITED_COUNT`: 방문한 고유 문화재 수.
   - `POINT_DONATION_COUNT`: `LEAVE_TO_BUYEO`를 선택하고 `settled_points > 0`인 여행 정산 수.
   - `QUIZ_CORRECT_COUNT`: 정답으로 제출한 객관식·OX 퀴즈 수. 사진 인증 제출은 포함하지 않는다.
   - `PHOTO_SUBMISSION_COUNT`: 전체 여행에서 제출한 `PHOTO` 유형 mission submission 수.
   - `QUIZ_CORRECT_WITHIN_60_MINUTES_COUNT`: 정답으로 제출한 객관식·OX 퀴즈를 시간순으로 훑어 60분 이내(경계 포함)에 들어오는 최대 연속 정답 수.
   - `QUIZ_CORRECT_STREAK`: 제출 시각순으로 정렬한 퀴즈(객관식·OX) 답안 중 지금까지 달성한 최대 연속 정답 수. 오답이 나오면 연속이 끊기고 다른 여행의 제출이 섞여도 끊기지 않는다.
3. 진행도와 상태는 원본 활동 데이터 및 배지 획득 이력으로 계산한다.
4. 같은 배지는 한 회원에게 한 번만 지급하고 획득 상태는 되돌리지 않는다.
5. 지급이 중단된 배지는 새로 지급하지 않지만 기존 획득 이력은 유지한다.
6. 미획득 배지도 각 조건과 현재 진행도를 조회할 수 있다.
7. 배지는 탐험, 퀴즈, 기록, 재화, 특별 분야로 구분한다.
8. 배지를 획득하면 획득을 유발한 여행 ID를 함께 저장하며 이후 다른 여행에서 같은 배지를 다시 연결하지 않는다.
9. 배지 판정은 activity를 처리하는 application service가 source 변경을 같은 transaction에서 query할 수 있게 flush한 뒤 badge의 공개 application service를 동기 호출하며, 변경된 metric에 관련된 미획득 배지만 판정한다.
10. 하나의 미션 완료에서 mission 완료와 방문 기록 생성이 함께 발생하면 변경된 두 metric을 한 번에 판정한다.
11. Activity, 새 배지 획득과 persistent `BADGE` 알림은 하나의 transaction으로 확정한다.
12. `BADGE` 알림은 새로 획득한 배지마다 한 건 만들며 `target_type`은 `BADGE`, `target_id`는 badge ID다.
13. Activity response의 `newlyAwardedBadges`는 새로 획득한 배지를 badge ID 오름차순으로 반환하고, 없으면 빈 배열을 반환한다.
14. 매일 `03:00 Asia/Seoul`에 활성 회원의 미획득 배지를 원본 활동 데이터로 reconciliation한다. 회원별 transaction은 member row를 lock하고 `ACTIVE` 상태를 다시 확인한다.
15. Reconciliation 지급은 실행 시각을 획득 시각으로 기록하고 조건에 기여한 가장 최근 활동의 여행에 연결하며, 실시간 지급과 같은 알림을 만든다.
16. 지급 가능한 배지는 하나 이상의 조건을 가져야 하며 모든 metric에 지원 Provider가 있어야 한다. 애플리케이션은 startup에 이를 검증하고 잘못된 catalog가 있으면 시작하지 않는다.
17. Idempotency replay는 최초 처리의 신규 획득 결과를 재사용하되 Presigned image URL은 저장하지 않고 응답마다 새로 생성한다.
18. 실시간 배지의 획득 시각은 지급을 유발한 activity가 확정된 시각이며, source application service가 이 시각을 배지의 공개 application service에 전달한다.
19. 배지 진척도 목록·상세 조회에서 지급이 중단됐고 아직 획득하지 않은 배지는 제외한다. 이미 획득한 배지는 지급 중단 여부와 무관하게 계속 노출한다.
20. 배지 진척도 조회에서 조건의 진행값은 threshold를 초과해도 threshold로 캡해 반환한다.
21. 배지 목록은 분야(category) 순서, 같은 분야 내에서는 배지 ID 오름차순으로 정렬한다. category로 필터링하면 획득 수와 전체 수는 필터링된 결과 기준으로 계산한다.
22. 미획득 배지는 조건 중 하나 이상의 진행값이 0보다 크면 진행 중 상태이고, 모든 조건의 진행값이 0이면 미획득 상태다.

## 상태 전이

| 모델 | 허용 상태 전이 | 규칙 |
| --- | --- | --- |
| 배지 | 미획득 → 진행 중 → 획득 | 획득한 배지는 미획득 상태로 되돌리지 않는다. |

## 관련 정책과 ADR

- [중복 요청과 멱등성](../../policies/idempotency.md)
- [날짜와 시간대](../../policies/date-time.md)
- [사용자 권한](../../policies/authorization.md)
- [동시 요청](../../policies/concurrency.md)
- [트랜잭션과 롤백](../../policies/transactions.md)
- [ADR-003 배지 메트릭 Provider](../../adr/adr-003-badge-metric-provider.md)
- [ADR-004 명시적 생명주기 상태](../../adr/adr-004-explicit-lifecycle-status.md)
- [ADR-010 모듈러 모놀리스와 인프로세스 작업](../../adr/adr-010-modular-monolith.md)
