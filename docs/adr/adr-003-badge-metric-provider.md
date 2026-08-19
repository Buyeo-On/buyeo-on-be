# ADR-003: 배지는 메트릭 Provider와 데이터 조건으로 판정한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
- **수정일:** 2026-08-19
## 맥락
배지는 미션 완료 수, 방문한 고유 문화재 수, 포인트 기부 정산 횟수처럼 집계 방식과 임계값이 다르며 여러 조건을 함께 가질 수 있다. 배지 추가·수정이 원본 테이블 구조나 배지별 코드에 직접 종속되지 않아야 한다.
## 결정
- 배지 판정은 Spring Boot 내부에서 처리한다.
- `badge_conditions`에 배지별 `metric_key`와 `threshold`를 저장하고 모든 조건을 `AND`로 판정한다.
- `BadgeMetricProvider`가 메트릭별 원본 활동 데이터를 집계하고 Registry가 메트릭에 맞는 Provider를 선택한다.
- 지원 메트릭은 회원의 전체 여행을 누적하는 `MISSION_COMPLETED_COUNT`, `HERITAGE_VISITED_COUNT`, `POINT_DONATION_COUNT`다.
  - 미션 완료 수는 완료한 mission participation 수이며 다른 여행에서 같은 mission을 다시 완료하면 다시 센다.
  - 문화재 방문 수는 회원이 방문한 고유 문화재 수다.
  - 포인트 기부 수는 `LEAVE_TO_BUYEO`를 선택하고 `settled_points > 0`인 정산 수다.
- 기존 메트릭을 사용하는 배지는 데이터만 추가·수정한다.
- 새로운 메트릭은 Provider와 source application service 연결 코드를 추가한다.
- `member_badges`는 획득 이력과 획득을 유발한 여행 ID만 저장하며 진행도와 상태는 원본 데이터 및 획득 이력에서 계산한다.
- 배지 지급은 `(member_id, badge_id)` 유일성으로 중복을 방지한다.
- Activity를 처리하는 application service는 source 변경을 마친 뒤 badge의 공개 application service를 동기 호출하고 새로 지급된 배지를 받는다. Spring application event는 결과를 반환할 수 없으므로 배지 판정 trigger로 사용하지 않는다.
- Source application service는 badge를 호출하기 전에 JPA persistence context를 flush해 같은 transaction의 Provider query가 방금 변경한 source row를 반드시 포함하게 한다.
- Source activity, 새 배지 획득과 persistent `BADGE` 알림은 같은 transaction으로 확정한다.
- 새로 지급된 배지는 badge ID 오름차순으로 activity response에 반환한다.
- Idempotency에는 새로 지급된 badge ID, 이름, 표시 조건, 획득 시각과 image key를 semantic result로 보관하고 Presigned image URL은 보관하지 않는다. 최초 응답과 replay마다 10분 유효한 URL을 새로 생성한다.
- 매일 `03:00 Asia/Seoul`에 활성 회원의 미획득 배지를 reconciliation한다. 과거 활동으로 지급하면 실행 시각을 획득 시각으로, 조건에 기여한 가장 최근 활동의 여행을 획득 여행으로 기록한다.
- 애플리케이션 startup에 지급 가능한 배지가 조건을 하나 이상 가지며 모든 metric Provider가 존재하는지 검증한다.
## 구현 예시
다음 코드는 구조를 설명하기 위한 예시이며 실제 패키지와 타입 이름은 구현 시 조정한다.
### 메트릭과 Provider
```java
public enum BadgeMetric {
    MISSION_COMPLETED_COUNT,
    HERITAGE_VISITED_COUNT,
    POINT_DONATION_COUNT
}

public interface BadgeMetricProvider {
    BadgeMetric metric();
    BadgeMetricSnapshot calculate(UUID memberId);
}

public record BadgeMetricSnapshot(
    long value,
    UUID latestContributingTripId,
    Instant latestContributionAt
) {
}

@Component
public class HeritageVisitedCountProvider implements BadgeMetricProvider {
    private final HeritageVisitMetricQuery metricQuery;

    @Override
    public BadgeMetric metric() {
        return BadgeMetric.HERITAGE_VISITED_COUNT;
    }

    @Override
    public BadgeMetricSnapshot calculate(UUID memberId) {
        return metricQuery.snapshot(memberId);
    }
}
```

`HeritageVisitMetricQuery`는 trip 도메인이 제공하는 공개 조회 seam이다. Provider는 다른 도메인의 Repository를 직접 사용하지 않는다. Mission과 point metric도 같은 방식으로 각 source 도메인의 공개 seam을 사용한다.
### Provider Registry
Spring이 모든 Provider를 주입하고 메트릭을 키로 사용해 조회한다.
```java
@Component
public class BadgeMetricProviderRegistry {
    private final Map<BadgeMetric, BadgeMetricProvider> providers;

    public BadgeMetricProviderRegistry(List<BadgeMetricProvider> providers) {
        this.providers = providers.stream()
            .collect(Collectors.toUnmodifiableMap(
                BadgeMetricProvider::metric,
                Function.identity()
            ));
    }

    public BadgeMetricProvider get(BadgeMetric metric) {
        var provider = providers.get(metric);
        if (provider == null) {
            throw new IllegalArgumentException("지원하지 않는 배지 메트릭: " + metric);
        }
        return provider;
    }
}
```
### 복합 조건 판정
한 번의 판정에서 같은 메트릭을 중복 집계하지 않도록 현재값을 캐시한다.
```java
@Service
public class BadgeEvaluationService {
    private final BadgeMetricProviderRegistry providerRegistry;
    private final BadgeRepository badgeRepository;
    private final MemberBadgeRepository memberBadgeRepository;
    private final BadgeNotificationService badgeNotificationService;

    @Transactional
    public List<AwardedBadge> award(
        UUID memberId,
        UUID tripId,
        Instant occurredAt,
        Set<BadgeMetric> affectedMetrics
    ) {
        Map<BadgeMetric, BadgeMetricSnapshot> values = new EnumMap<>(BadgeMetric.class);
        List<AwardedBadge> awarded = new ArrayList<>();

        for (var badge : badgeRepository.findNotEarnedByAnyMetric(
            memberId,
            affectedMetrics
        )) {
            boolean achieved = badge.conditions().stream().allMatch(condition -> {
                long currentValue = values.computeIfAbsent(
                    condition.metric(),
                    metric -> providerRegistry.get(metric).calculate(memberId)
                ).value();
                return currentValue >= condition.threshold();
            });

            if (achieved) {
                boolean inserted = memberBadgeRepository.awardIfAbsent(
                    memberId,
                    badge.id(),
                    tripId,
                    occurredAt
                );
                if (inserted) {
                    badgeNotificationService.create(memberId, badge, occurredAt);
                    awarded.add(AwardedBadge.from(badge, occurredAt));
                }
            }
        }

        return awarded;
    }
}
```
Repository는 지급이 중단된 배지를 제외하고 badge ID 오름차순으로 조회한다. `awardIfAbsent`가 실제 획득 이력을 만든 경우에만 알림과 response 항목을 생성하므로 실시간 판정과 reconciliation이 경합해도 중복 결과를 만들지 않는다.

### Source application service 연결

미션 완료는 source 상태, 방문 기록과 포인트를 먼저 변경하고 persistence context를 flush한 뒤 이번 transaction에서 바뀐 metric을 한 번에 전달한다. Provider query가 방금 완료한 activity를 포함하는지는 실제 PostgreSQL을 사용하는 transaction 통합 테스트로 검증한다.

```java
Set<BadgeMetric> affectedMetrics = EnumSet.of(MISSION_COMPLETED_COUNT);
if (visitRecorded) {
    affectedMetrics.add(HERITAGE_VISITED_COUNT);
}

List<AwardedBadge> newlyAwardedBadges = badgeEvaluationService.award(
    memberId,
    tripId,
    occurredAt,
    affectedMetrics
);
```

양수 포인트를 부여에 남기는 정산은 같은 service에 `POINT_DONATION_COUNT`를 전달한다. Activity의 idempotency record에는 `newlyAwardedBadges`의 semantic result와 image key를 함께 저장한다. Replay에서는 badge를 다시 판정하지 않고 image key로 Presigned URL만 새로 생성한다.

### Reconciliation과 catalog 검증

Scheduler는 매일 `03:00 Asia/Seoul`에 활성 회원을 대상으로 미획득 배지를 판정한다. 회원별 transaction은 member row를 먼저 잠그고 상태를 다시 확인하며 `ACTIVE`가 아니면 건너뛴다. 따라서 탈퇴와 같은 lock order를 사용한다. 한 회원의 실패는 다른 회원을 막지 않으며 실패한 회원은 다음 실행에서 다시 판정한다.

Reconciliation으로 지급할 때는 모든 조건의 `BadgeMetricSnapshot`을 `latestContributionAt` 내림차순, metric key 오름차순, trip ID 오름차순으로 정렬한 첫 trip을 연결한다. Mission과 point metric은 조건에 해당하는 가장 최근 완료·기부 row를 사용하고, `HERITAGE_VISITED_COUNT`는 고유 문화재 수에 실제로 기여한 각 문화재의 최초 방문 중 가장 최근 row를 사용한다. 지급과 알림 규칙은 실시간 판정과 같지만 triggering response는 없다.

Startup validator는 지급이 중단되지 않은 각 배지가 다음 조건을 만족하는지 확인한다.

- `badge_conditions`가 하나 이상 존재한다.
- 모든 `metric_key`에 등록된 Provider가 있다.

조건을 만족하지 않으면 사용자 activity 처리 중 발견하지 않고 애플리케이션 시작을 실패시킨다.
## 결과
- 같은 메트릭의 배지와 복합 조건 배지를 코드 변경 없이 구성할 수 있다.
- 새로운 집계 종류는 코드 배포가 필요하지만 공통 판정 서비스와 DB 구조는 바뀌지 않는다.
- 배지별 진행값을 저장하지 않아 원본 활동 데이터와의 불일치를 피한다.
- Activity response가 새로 획득한 배지를 즉시 제공하며 획득 이력과 persistent 알림이 원자적으로 일치한다.
- Reconciliation이 새 catalog 도입 전에 조건을 충족한 회원도 이후 지급한다.
## 기각한 대안
- 배지마다 전용 판정 코드를 작성하는 방식
- 조건을 임의 SQL·JSON 표현식으로 저장하는 범용 규칙 엔진
- 결과를 반환하지 못하는 Spring application event만으로 activity와 배지 판정을 연결하는 방식
- Activity와 배지 획득을 분리해 별도 retry나 outbox로 연결하는 방식
- MVP에서 Spring → API Gateway → Lambda로 배지를 판정하는 방식
