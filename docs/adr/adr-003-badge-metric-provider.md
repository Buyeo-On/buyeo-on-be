# ADR-003: 배지는 메트릭 Provider와 데이터 조건으로 판정한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
## 맥락
배지는 미션 완료 수, 방문한 고유 문화재 수, 포인트 기부 정산 횟수처럼 집계 방식과 임계값이 다르며 여러 조건을 함께 가질 수 있다. 배지 추가·수정이 원본 테이블 구조나 배지별 코드에 직접 종속되지 않아야 한다.
## 결정
- 배지 판정은 Spring Boot 내부에서 처리한다.
- `badge_conditions`에 배지별 `metric_key`와 `threshold`를 저장하고 모든 조건을 `AND`로 판정한다.
- `BadgeMetricProvider`가 메트릭별 원본 활동 데이터를 집계하고 Registry가 메트릭에 맞는 Provider를 선택한다.
- 지원 메트릭은 `MISSION_COMPLETED_COUNT`, `HERITAGE_VISITED_COUNT`, `POINT_DONATION_COUNT`다.
- 기존 메트릭을 사용하는 배지는 데이터만 추가·수정한다.
- 새로운 메트릭은 Provider와 관련 도메인 사건 연결 코드를 추가한다.
- `member_badges`는 획득 이력만 저장하며 진행도와 상태는 원본 데이터 및 획득 이력에서 계산한다.
- 배지 지급은 `(member_id, badge_id)` 유일성으로 중복을 방지한다.
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
    long calculate(UUID memberId);
}

@Component
public class HeritageVisitedCountProvider implements BadgeMetricProvider {
    private final VisitRepository visitRepository;

    @Override
    public BadgeMetric metric() {
        return BadgeMetric.HERITAGE_VISITED_COUNT;
    }

    @Override
    public long calculate(UUID memberId) {
        return visitRepository.countDistinctPlacesByMemberId(memberId);
    }
}
```
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

    @Transactional
    public void evaluate(UUID memberId, BadgeMetric affectedMetric) {
        Map<BadgeMetric, Long> values = new EnumMap<>(BadgeMetric.class);

        for (var badge : badgeRepository.findNotEarnedByMetric(
            memberId,
            affectedMetric
        )) {
            boolean achieved = badge.conditions().stream().allMatch(condition -> {
                long currentValue = values.computeIfAbsent(
                    condition.metric(),
                    metric -> providerRegistry.get(metric).calculate(memberId)
                );
                return currentValue >= condition.threshold();
            });

            if (achieved) {
                memberBadgeRepository.awardIfAbsent(memberId, badge.id());
            }
        }
    }
}
```
### 도메인 사건 연결
`@EventListener`는 기본적으로 같은 스레드에서 동기 실행된다.
```java
@Component
public class BadgeEventListener {
    private final BadgeEvaluationService badgeEvaluationService;

    @EventListener
    public void on(MissionCompleted event) {
        badgeEvaluationService.evaluate(
            event.memberId(),
            BadgeMetric.MISSION_COMPLETED_COUNT
        );
    }

    @EventListener
    public void on(VisitRecorded event) {
        badgeEvaluationService.evaluate(
            event.memberId(),
            BadgeMetric.HERITAGE_VISITED_COUNT
        );
    }

    @EventListener
    public void on(PointsDonated event) {
        badgeEvaluationService.evaluate(
            event.memberId(),
            BadgeMetric.POINT_DONATION_COUNT
        );
    }
}
```
## 결과
- 같은 메트릭의 배지와 복합 조건 배지를 코드 변경 없이 구성할 수 있다.
- 새로운 집계 종류는 코드 배포가 필요하지만 공통 판정 서비스와 DB 구조는 바뀌지 않는다.
- 배지별 진행값을 저장하지 않아 원본 활동 데이터와의 불일치를 피한다.
## 기각한 대안
- 배지마다 전용 판정 코드를 작성하는 방식
- 조건을 임의 SQL·JSON 표현식으로 저장하는 범용 규칙 엔진
- MVP에서 Spring → API Gateway → Lambda로 배지를 판정하는 방식
