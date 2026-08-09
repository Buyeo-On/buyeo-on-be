---
name: domain-modeling
description: 부여ON의 도메인 용어, 규칙, 유즈케이스와 아키텍처 결정을 기존 docs 구조 안에서 정리한다. 그릴링 중 용어를 확정하거나 전역 규칙·API·DB·ADR 변경이 발견될 때 사용한다.
---

# Domain modeling

새로운 `CONTEXT.md`를 만들지 않는다. 프로젝트의 기존 문서를 역할에 따라 유지한다.

## 문서별 책임

| 문서 | 기록할 내용 |
| --- | --- |
| `docs/prd.md` | 제품 목표, MVP 범위, 가설 |
| `docs/domains/README.md` | 도메인 경계, 책임과 코드 패키지의 대응 |
| `docs/domains/<domain>/rules.md` | 해당 도메인의 표준 용어, 모델 관계, 불변식과 상태 전이 |
| `docs/domains/<domain>/use-cases/*.md` | 사용자 목표, 사전 조건, 기본·예외 흐름과 context routing frontmatter |
| `docs/domains/<domain>/api.md` | 해당 도메인이 소유하는 OpenAPI operation 인덱스 |
| `docs/policies/` | 여러 도메인에 공통으로 적용되는 규칙 |
| `docs/use-cases.md` | 개별 유즈케이스 파일을 찾는 인덱스 |
| `docs/architecture.md` | 현재 채택된 시스템 구성과 운영 방식 |
| `docs/adr/` | 되돌리기 어렵고 트레이드오프가 있는 설계 결정 |
| `docs/raw/openapi.yaml` | API 계약의 원본 |
| `docs/api-spec.md` | API 공통 규약과 도메인 API 인덱스 |
| `docs/raw/db-schema.sql` | 데이터베이스 스키마 원본 |

## 작업 방식

1. 대화에서 모호하거나 같은 뜻으로 혼용되는 용어를 찾는다.
2. 대상 유즈케이스의 `owner` 도메인 `rules.md`에 있는 표준 용어와 대조한다.
3. 구체적인 경계 사례로 용어와 관계를 검증한다.
4. 코드·API·DB와 문서의 주장을 교차 확인한다.
5. 합의된 사실은 가장 적절한 원본 문서 한 곳에 즉시 반영하고 파생 문서를 동기화한다.
6. 유즈케이스의 참여 도메인, 정책, API나 ADR이 바뀌면 frontmatter도 함께 갱신한다.
7. 새 유즈케이스 파일을 추가하면 `docs/use-cases.md` 인덱스를 갱신한다.
8. 기존 규칙과 충돌하면 덮어쓰지 말고 충돌과 필요한 결정을 먼저 알린다.

## ADR 기준

다음 조건을 모두 만족할 때만 ADR을 추가하거나 기존 ADR을 대체한다.

- 되돌리는 비용이 크다.
- 맥락을 모르면 결정이 의외로 보인다.
- 실제 대안과 트레이드오프를 검토했다.

ADR은 기존 형식인 `상태`, `결정일`, `맥락`, `결정`, `결과`, `기각한 대안`을 따른다. 새 ADR을 추가하면 `docs/adr/README.md`에도 링크한다.

구현 일정, 담당자, 작업 분할과 이번 전달 범위처럼 실행 관리에만 필요한 정보는 Epic에 기록한다. 제품 동작, 도메인 규칙, API·DB 계약과 아키텍처 결정처럼 이후에도 유효한 사실은 적용 범위가 한 유즈케이스뿐이어도 책임 문서에 남긴다.
