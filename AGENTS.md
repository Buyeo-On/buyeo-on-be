# Backend Agent Guide

구현하거나 설계를 변경하기 전에 작업 범위와 관련된 문서를 확인한다. 문서와 구현이 충돌하면 임의로 해석하지 말고 차이를 명시한다.

## 작업 흐름

기본 개발 단위는 유즈케이스다. 유즈케이스 하나를 GitHub Epic Issue 하나로 관리하고, 구현은 하나 이상의 수직 슬라이스 Sub-issue로 나눈다. 지나치게 큰 유즈케이스는 독립적으로 인수 검증할 수 있는 유즈케이스와 Epic으로 먼저 나눈다.

```text
유즈케이스 선택
→ grill-with-docs 스킬
→ 프로젝트 문서 갱신
→ to-spec 스킬로 Epic Issue 생성
→ to-tickets 스킬로 Sub-issue 생성
→ implement 스킬
→ code-review 스킬
→ Epic 통합 인수 검증
```

- 그릴링에서 확정한 지속적인 제품·도메인·API·DB·아키텍처 사실은 적용 범위가 한 유즈케이스뿐이어도 Epic에만 남기지 말고 책임 문서에 먼저 반영한다.
- Epic은 목표, 범위, 인수 조건과 의존성을 관리한다. 기존 문서의 내용을 그대로 복제하지 않고 링크와 이번 작업의 차이만 기록한다.
- Sub-issue는 `DB`, `Repository`, `Service`, `Controller` 같은 계층이 아니라 독립적으로 검증 가능한 동작 단위로 나눈다.
- 각 Sub-issue는 Parent Epic, 인수 조건, 테스트 seam, 관련 문서, blocker와 범위 제외를 명시한다.
- blocker가 없는 Sub-issue만 구현한다. 한 Sub-issue는 한 작업 브랜치와 한 PR을 기본으로 한다.
- 구현자는 자신의 PR을 최종 승인하지 않는다. 리뷰는 Standards와 Spec 두 축으로 수행한다.
- 모든 Sub-issue가 끝나면 Epic의 전체 사용자 흐름과 인수 조건을 다시 검증한 뒤 닫는다.

## 공통 요구사항

- [PRD](./docs/prd.md)
- [도메인 지도](./docs/domains/README.md)
- [전역 정책](./docs/policies/README.md)
- [유즈케이스 인덱스](./docs/use-cases.md)

## 컨텍스트 로딩

전체 문서를 기본으로 읽지 않는다. 작업할 유즈케이스를 기준으로 다음 순서대로 필요한 context pack만 구성한다.

1. `docs/use-cases.md`에서 대상 유즈케이스 파일을 찾는다.
2. 유즈케이스 frontmatter의 `owner` 도메인에 있는 `rules.md`와 `api.md`를 읽는다.
3. `participants` 도메인은 이번 작업과 맞닿은 규칙과 API만 추가로 읽는다.
4. `policies`와 `adrs`에 명시된 문서만 읽는다.
5. 구현할 operation과 테이블만 `docs/raw/openapi.yaml`, `docs/raw/db-schema.sql`에서 확인한다.
6. 제품 범위나 시스템 구성을 결정할 때만 `docs/prd.md`, `docs/architecture.md`를 추가로 읽는다.

유즈케이스의 frontmatter가 실제 의존 문서를 빠뜨렸다면 구현 전에 먼저 고친다. 관련 없는 다른 도메인의 전체 문서를 선제적으로 읽지 않는다.

## 설계

- [아키텍처](./docs/architecture.md)
- [Architecture Decision Records](./docs/adr/README.md)

## API와 데이터베이스

- [API 명세](./docs/api-spec.md)
- [OpenAPI 원본](./docs/raw/openapi.yaml)
- [데이터베이스 스키마](./docs/raw/db-schema.sql)

## 문서 책임과 동기화

- 제품 목표와 MVP 범위는 `docs/prd.md`에 기록한다.
- 도메인 경계와 코드 패키지의 대응은 `docs/domains/README.md`에 기록한다.
- 도메인 모델, 용어, 불변식과 상태 전이는 `docs/domains/<domain>/rules.md`에 기록한다.
- 여러 도메인에 적용되는 규칙은 `docs/policies/`에 기록한다.
- 사용자 목표, 사전 조건과 기본·예외 흐름은 주도 도메인의 `use-cases/` 아래 개별 파일에 기록하고 `docs/use-cases.md` 인덱스를 갱신한다.
- 현재 채택된 시스템 구성은 `docs/architecture.md`, 결정의 맥락과 대안은 `docs/adr/`에 기록한다.
- API 계약의 원본은 `docs/raw/openapi.yaml`이다. 변경 시 소유 도메인의 `api.md`, `docs/api-spec.md` 공통 규약과 구현을 일치시킨다.
- 데이터베이스 스키마의 원본은 `docs/raw/db-schema.sql`이다. 구현 단계에서는 Flyway 마이그레이션과 일치시킨다.
- 문서 간 충돌을 발견하면 조용히 한쪽을 선택하지 않는다. 충돌과 영향을 먼저 명시하고 필요한 결정을 확정한 뒤 함께 수정한다.

## 구현과 검증

- Java 21과 기존 Spring Boot·Gradle 구성을 유지하고 요청받지 않은 의존성이나 프레임워크를 추가하지 않는다.
- 도메인 패키지 경계를 지키고 다른 도메인의 Repository를 직접 사용하지 않는다.
- 테스트는 합의된 공개 seam에서 관찰 가능한 동작을 검증한다. 내부 구현 세부사항에 결합된 테스트를 피한다.
- 관련 테스트를 작업 중 반복 실행하고 종료 전에 `./gradlew test`를 실행한다.
- 변경사항은 Sub-issue 인수 조건, Parent Epic, 도메인 규칙, OpenAPI와 DB 계약을 기준으로 검토한다.
- 커밋, 푸시, PR 생성, Issue 종료와 merge는 사용자가 명시적으로 요청한 경우에만 수행한다.
