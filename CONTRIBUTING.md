# 부여ON 백엔드 기여 가이드

부여ON 백엔드는 **유즈케이스를 설계 단위**, **독립적으로 검증 가능한 수직 슬라이스를 구현 단위**로 개발합니다. 하나의 유즈케이스는 하나의 GitHub Epic으로 관리하고, 구현은 하나 이상의 Sub-issue로 나눕니다.

이 문서는 프로젝트 Skills를 이용해 요구사항을 구체화하고, 문서·Issue·코드·테스트를 일관되게 유지하는 절차를 설명합니다. 세부 규칙은 먼저 [AGENTS.md](./AGENTS.md)를 따릅니다.

## 개발 환경

- Java 21
- 프로젝트에 포함된 Gradle Wrapper (`./gradlew`)
- Docker 및 Docker Compose
- Epic과 Sub-issue를 발행할 때 사용할 GitHub CLI (`gh`)

로컬 환경 변수는 [`.env.example`](./.env.example)을 참고합니다. 새로운 프레임워크나 의존성은 합의 없이 추가하지 않습니다.

## 전체 개발 흐름

```text
유즈케이스 선택
→ grill-with-docs
→ 프로젝트 문서 갱신
→ to-spec으로 Epic 생성
→ to-tickets로 Sub-issue 생성
→ 구현 가능한 Sub-issue마다 implement
   └─ tdd → 검증 스크립트 → code-review
→ PR 리뷰 및 병합
→ Epic 통합 인수 검증
```

최상위 작업인 `grill-with-docs`, `to-spec`, `to-tickets`, `implement`는 이름을 명시해 요청합니다. `grilling`과 `domain-modeling`은 `grill-with-docs`가, `tdd`와 `code-review`는 `implement`가 내부 단계로 사용합니다. Issue 생성, 구현, 커밋, 푸시, PR 생성, Issue 종료와 병합은 자동으로 진행되는 작업이 아닙니다. 에이전트에게 맡길 때는 원하는 작업을 명시적으로 요청해야 합니다.

## 1. 유즈케이스 선택

1. [유즈케이스 인덱스](./docs/use-cases.md)에서 작업할 유즈케이스를 선택합니다.
2. 유즈케이스 하나가 하나의 Epic으로 다루기에 너무 크다면, 각각 독립적으로 인수 검증할 수 있는 유즈케이스로 먼저 나눕니다.
3. 대상 유즈케이스 파일의 frontmatter를 기준으로 필요한 context pack만 읽습니다.
   - `owner`: 소유 도메인의 `rules.md`, `api.md`
   - `participants`: 이번 흐름과 맞닿은 참여 도메인의 규칙과 API
   - `policies`: 지정된 전역 정책
   - `adrs`: 지정된 ADR
   - `api`: 구현 대상 OpenAPI operation
4. 구현할 operation과 테이블만 `docs/raw/openapi.yaml`, `docs/raw/db-schema.sql`에서 확인합니다.
5. 제품 범위나 시스템 구성을 결정할 때만 `docs/prd.md`, `docs/architecture.md`를 추가로 읽습니다.

관련 없는 도메인 문서나 전체 API·DB 명세를 선제적으로 읽지 않습니다. 필요한 의존 문서가 frontmatter에서 빠졌다면 다음 단계 전에 먼저 고칩니다.

## 2. `grill-with-docs`: 요구사항과 경계 확정

예시 요청:

> UC-09 퀴즈 풀이를 `grill-with-docs`로 검토해 주세요.

`grill-with-docs`는 내부적으로 다음 두 Skill의 규칙을 함께 적용합니다.

- `grilling`: 사용자 목표, 상태 전이, 권한, 중복·동시 요청, 트랜잭션, 실패 복구, 범위 제외와 인수 조건을 검토합니다.
- `domain-modeling`: 합의한 용어와 지속적인 제품·도메인·API·DB·아키텍처 사실을 책임 문서에 반영합니다.

이 단계에서는 Epic을 만들거나 구현을 시작하지 않습니다. 문서와 코드가 충돌하면 임의로 한쪽을 선택하지 말고 차이와 영향을 결정 대상으로 올립니다.

다음을 모두 확인하면 그릴링을 종료합니다.

- 미해결 설계 질문이 없습니다.
- 목표, 범위와 범위 제외가 명확합니다.
- 기본·대안·예외 흐름이 합의되었습니다.
- 선행 의존성과 검증 가능한 인수 조건이 정해졌습니다.
- 사용자가 공통 이해를 확인했습니다.

## 3. 프로젝트 문서 갱신

그릴링에서 확정한 사실은 Epic보다 먼저 책임 문서에 기록합니다.

| 변경 내용 | 책임 문서 |
| --- | --- |
| 제품 목표와 MVP 범위 | `docs/prd.md` |
| 도메인 경계와 코드 패키지 대응 | `docs/domains/README.md` |
| 용어, 모델 관계, 불변식과 상태 전이 | `docs/domains/<domain>/rules.md` |
| 사용자 목표, 사전 조건, 기본·예외 흐름과 context routing frontmatter | `docs/domains/<domain>/use-cases/*.md` |
| 여러 도메인에 적용되는 규칙 | `docs/policies/` |
| 현재 시스템 구성 | `docs/architecture.md` |
| 되돌리기 어렵고, 맥락 없이는 의외이며, 대안과 트레이드오프를 검토한 결정 | `docs/adr/` |
| API 계약 원본 | `docs/raw/openapi.yaml` |
| 데이터베이스 스키마 원본 | `docs/raw/db-schema.sql` |

유즈케이스의 참여 도메인, 정책, API 또는 ADR이 달라졌다면 frontmatter도 갱신합니다. 새 유즈케이스 파일을 만들었다면 `docs/use-cases.md`에 링크를 추가합니다. OpenAPI를 변경할 때는 소유 도메인의 `api.md`, `docs/api-spec.md`의 공통 규약과 구현을, DB 스키마를 변경할 때는 Flyway 마이그레이션과 구현을 함께 맞춥니다.

## 4. `to-spec`: Epic 발행

예시 요청:

> 방금 확정한 UC-09 그릴링 결과를 `to-spec`으로 Epic Issue로 만들어 주세요.

`to-spec`은 완료된 그릴링 결과와 프로젝트 문서를 합성해 [Epic 템플릿](./.github/ISSUE_TEMPLATE/epic.md)을 채웁니다.

1. 문서 변경이 Epic보다 먼저 반영되었는지 확인합니다.
2. `gh auth status`와 `gh repo view`로 인증과 대상 저장소를 확인하되 비밀 값은 출력하지 않습니다.
3. 기존 Epic을 검색해 중복 생성을 피합니다.
4. 가장 높은 공개 테스트 seam을 정합니다. 기본값은 HTTP API부터 실제 DB까지 통과하는 통합 테스트입니다.
5. 목표, 범위, 범위 제외, 사용자 흐름, 인수 조건, 구현·테스트 결정과 의존성을 작성합니다.
6. 문서 내용을 복사하지 않고 링크와 이번 Epic에서 달라지는 범위만 기록합니다.
7. 초안을 검토하고 승인한 뒤 Issue를 발행합니다.

인수 조건을 관찰 가능한 동작으로 작성할 수 있을 만큼 결정이 끝나지 않았다면 Epic을 만들지 말고 `grill-with-docs`로 돌아갑니다.

## 5. `to-tickets`: 수직 슬라이스 분할

예시 요청:

> Epic #123을 `to-tickets`로 구현 가능한 Sub-issue로 나눠 주세요.

`to-tickets`는 [Sub-issue 템플릿](./.github/ISSUE_TEMPLATE/ticket.md)에 따라 Epic을 한 컨텍스트에서 구현·리뷰할 수 있는 크기로 나눕니다.

- `DB`, `Repository`, `Service`, `Controller` 같은 기술 계층별 티켓을 만들지 않습니다.
- 각 티켓은 가능한 한 스키마부터 API와 테스트까지 연결되는 좁고 완결된 수직 슬라이스여야 합니다.
- 각 티켓만 완료해도 독립적으로 시연하거나 검증할 수 있어야 합니다.
- Parent Epic, 범위, 인수 조건, 테스트 seam, 관련 문서, blocker와 범위 제외를 반드시 명시합니다.
- 선행 작업이 필요하면 blocking 관계를 연결합니다.
- 후속 변경을 가능하게 하는 선행 정리가 필요하면 독립적으로 검증 가능한 prefactor 티켓으로 분리할 수 있습니다.
- 넓은 전환 작업은 필요에 따라 `expand → migrate → contract` 순서로 나눕니다.

티켓 제목, 범위, 인수 조건과 dependency graph를 먼저 검토한 후 발행합니다. 발행이 끝나면 blocker가 없는 **현재 구현 가능 frontier**와 대기 중인 티켓을 구분합니다.

## 6. `implement`: Sub-issue 구현

예시 요청:

> Sub-issue #124를 `implement`로 구현해 주세요.

한 번에 준비된 Sub-issue 하나만 구현합니다.

### 구현 전

1. Sub-issue, Parent Epic과 blocker 상태를 확인합니다.
2. `AGENTS.md`, 연결된 유즈케이스와 frontmatter가 가리키는 context pack을 현재 작업 컨텍스트에서 다시 읽습니다.
3. blocker가 남아 있거나 Spec과 프로젝트 문서가 충돌하면 구현하지 않고 차이를 먼저 해결합니다.
4. `main`이 아닌 작업 브랜치인지 확인합니다.
5. 한 Sub-issue당 하나의 작업 브랜치와 하나의 PR을 기본으로 합니다.
6. 티켓의 인수 조건과 테스트 seam을 구현 계획으로 정리합니다.

### `tdd`: Red → Green → Refactor

`implement`는 `tdd` Skill을 사용해 인수 조건을 하나씩 구현합니다.

1. **Red:** 공개 seam에서 인수 조건 하나를 표현하는 실패 테스트를 작성하고, 의도한 이유로 실패하는지 확인합니다.
2. **Green:** 해당 테스트를 통과하는 최소 코드를 작성합니다.
3. 관련 테스트를 다시 실행합니다.
4. 다음 인수 조건에 대해 같은 과정을 반복합니다.
5. 모든 동작이 통과한 뒤 중복과 이름만 정리합니다.

기본 테스트 seam은 HTTP 요청부터 Spring application과 영속성까지 관찰하는 통합 seam입니다. 복잡한 도메인 규칙에는 공개 도메인 API를 통한 단위 테스트를 추가할 수 있습니다. private 메서드, 내부 호출 순서나 구현 클래스의 존재 여부를 테스트하지 않으며, Mock은 제어할 수 없는 외부 시스템 경계에 한정합니다.

구현 중에는 기존 패키지 경계와 공개 인터페이스를 우선 재사용합니다. 다른 도메인의 Repository를 직접 사용하거나 티켓 범위를 임의로 넓히지 않습니다.

### 구현 검증

관련 테스트를 수시로 실행하고, 구현이 끝나면 다음 스크립트를 순서대로 실행합니다.

```bash
./scripts/test/format.sh
./scripts/test/static-check.sh
./scripts/test/test.sh
./scripts/test/docker-build.sh
```

특정 테스트를 먼저 실행해야 한다면 다음 형식을 사용할 수 있습니다. 이 명령도 마지막에는 전체 테스트를 실행합니다.

```bash
./scripts/test/test.sh --tests 'com.buyeoon.example.ExampleTests'
```

## 7. `code-review`: Standards와 Spec 검토

`implement`의 종료 단계와 병합 전에는 `code-review`를 수행합니다.

예시 요청:

> Sub-issue #124 구현을 `code-review`로 검토해 주세요.

리뷰 기준점은 사용자가 지정한 commit·tag·branch를 사용하고, 별도 지정이 없으면 현재 브랜치와 기본 브랜치의 merge-base를 사용합니다. `<기준점>..HEAD`의 커밋과 diff가 유효한지 확인하고, 리뷰 대상인 staged·unstaged 변경이 있다면 index와 working tree diff도 별도로 포함합니다. 리뷰는 다음 두 축을 합치지 않고 각각 보고합니다.

### Standards

- `AGENTS.md`, 아키텍처와 ADR 준수
- 도메인 패키지 경계와 트랜잭션 범위
- 정확성, 보안, 동시성, 실패 처리
- 테스트 품질과 유지보수성
- 불필요한 일반화, 중복과 모호한 이름

### Spec

- Sub-issue 인수 조건 충족
- Parent Epic 목표와 범위 준수
- 유즈케이스와 도메인 규칙 준수
- OpenAPI·DB 계약과 구현 일치
- 범위 제외 기능을 추가하지 않았는지 확인

차단 문제를 수정한 뒤 관련 테스트와 전체 테스트를 다시 실행합니다. 구현자는 자신의 PR을 최종 승인하지 않습니다.

## 8. PR과 Epic 완료

PR에는 최소한 다음 내용을 기록합니다.

- Parent Epic과 구현한 Sub-issue
- 변경한 동작과 파일
- 충족한 인수 조건
- 실행한 검증과 결과
- 남은 위험 또는 후속 작업

모든 Sub-issue가 병합되면 개별 티켓의 통과 여부만으로 Epic을 종료하지 않습니다. Epic에 정의된 전체 사용자 흐름과 인수 조건을 통합 환경에서 다시 검증합니다. 이 검증이 끝난 뒤에만 Epic을 닫습니다.

## 완료 체크리스트

- [ ] 유즈케이스의 context pack만 필요한 범위로 확인했다.
- [ ] `grill-with-docs`로 요구사항과 경계를 확정했다.
- [ ] 지속되는 결정은 책임 문서에 먼저 반영했다.
- [ ] `to-spec`으로 승인된 Epic을 발행했다.
- [ ] `to-tickets`로 독립 검증 가능한 수직 슬라이스를 만들었다.
- [ ] blocker가 없는 Sub-issue만 별도 브랜치에서 구현했다.
- [ ] `tdd`로 인수 조건마다 Red → Green을 확인했다.
- [ ] 포맷, 정적 검사, 전체 테스트와 Docker 검증을 통과했다.
- [ ] `code-review`의 Standards와 Spec 검토에서 차단 문제가 없다.
- [ ] 독립 리뷰를 거쳐 PR을 병합했다.
- [ ] 모든 Sub-issue 완료 후 Epic의 통합 인수 조건을 다시 검증했다.
