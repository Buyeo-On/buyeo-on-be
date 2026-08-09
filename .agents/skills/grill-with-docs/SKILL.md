---
name: grill-with-docs
description: 사용자가 명시적으로 요청했을 때 선택한 부여ON 유즈케이스를 관련 프로젝트 문서와 함께 집요하게 검토하여 구현 가능한 Epic 명세의 재료로 구체화한다. 유즈케이스를 Epic으로 만들기 전 요구사항, 경계 조건, 의존성과 용어를 확정할 때 사용한다.
---

# Grill with docs

선택한 유즈케이스를 대상으로 `grilling` 스킬을 수행하면서 `domain-modeling` 스킬의 문서 규칙을 적용한다. 사용자의 명시적 요청 없이 이 오케스트레이션을 시작하지 않는다.

## 시작

1. 대상 유즈케이스 번호와 제목을 확인한다.
2. `AGENTS.md`와 `docs/use-cases.md`에서 대상 유즈케이스 파일을 찾는다.
3. 유즈케이스 frontmatter를 라우팅 정보로 사용한다.
   - `owner`: 해당 도메인의 `rules.md`, `api.md`
   - `participants`: 이번 흐름과 맞닿은 규칙과 API
   - `policies`: 지정된 `docs/policies/*.md`
   - `adrs`: 지정된 `docs/adr/*.md`
4. 구현할 operation과 테이블만 `docs/raw/openapi.yaml`, `docs/raw/db-schema.sql`에서 확인한다.
5. 제품 범위나 시스템 구성을 결정해야 할 때만 `docs/prd.md`, `docs/architecture.md`를 읽는다.
6. 코드와 GitHub의 선행 Epic·Issue가 있다면 함께 확인한다.

관련 없는 도메인 문서, 전체 OpenAPI와 전체 DB 스키마를 기본으로 읽지 않는다. frontmatter가 필요한 문서를 빠뜨렸다면 그릴링 전에 보완한다.

## 진행 원칙

- 저장소에서 확인할 수 있는 사실을 사용자에게 묻지 않는다.
- 유즈케이스의 사용자 목표를 구현 작업이나 화면 목록으로 바꾸지 않는다.
- 정상 흐름뿐 아니라 권한, 상태 전이, 멱등성, 동시성, 트랜잭션, 실패 복구와 범위 제외를 검토한다.
- 문서끼리 또는 문서와 코드가 충돌하면 차이를 명시하고 결정 대상으로 올린다.
- 합의된 지속적인 제품·도메인·API·DB·아키텍처 사실은 적용 범위가 한 유즈케이스뿐이어도 `domain-modeling`에 따라 책임 문서에 반영한다.
- Epic Issue는 이 단계에서 만들지 않는다. 합의된 내용을 `to-spec` 스킬이 Epic으로 발행한다.

## 종료 조건

설계 트리의 미해결 frontier가 없어지고 사용자가 공통 이해를 확인했을 때 종료한다. 마지막에 다음을 요약한다.

- 확정된 결정
- 수정한 문서
- 범위와 범위 제외
- 선행 의존성
- 남은 미결 사항
- `to-spec` 진행 가능 여부
