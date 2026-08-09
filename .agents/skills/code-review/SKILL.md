---
name: code-review
description: 고정된 Git 기준점 이후 변경을 프로젝트 Standards와 originating Epic·Sub-issue Spec 두 축으로 독립 검토한다. 구현 완료 후 병합 전 리뷰에 사용한다.
---

# Code review

변경사항을 **Standards**와 **Spec** 두 축으로 따로 검토하고 결과를 나란히 보고한다.

## 기준점 고정

1. 사용자가 준 commit·tag·branch가 있으면 고정 기준점으로 사용하고 `<fixed-point>..HEAD`를 직접 비교한다.
2. 없으면 현재 브랜치와 기본 브랜치의 merge-base를 계산해 고정 기준점으로 사용한다.
3. 계산된 기준점에 대해 다음 범위를 확인한다.

```bash
git diff <fixed-point>..HEAD
git log <fixed-point>..HEAD --oneline
```

기준점이 유효하고 diff가 비어 있지 않은지 먼저 확인한다. 커밋되지 않은 변경도 리뷰 대상이면 별도로 포함한다.

## Spec 출처

다음 순서로 originating spec을 찾는다.

1. 사용자가 지정한 Sub-issue와 Parent Epic
2. 브랜치명과 커밋 메시지의 Issue 번호
3. PR에 연결된 Issue
4. 관련 유즈케이스와 프로젝트 문서

Spec을 찾지 못하면 Standards만 수행하고 그 사실을 명시한다.

## 두 리뷰 축

가능하면 독립된 두 리뷰를 병렬로 수행한다.

### Standards

- `AGENTS.md`, 아키텍처와 ADR 준수
- 도메인 패키지 경계와 트랜잭션 범위
- 정확성, 보안, 동시성, 실패 처리
- 테스트 품질과 유지보수성
- 중복 코드, 모호한 이름, 기능 편애, 데이터 뭉치, 원시값 집착, 산탄총 수정, 불필요한 일반화

### Spec

- Sub-issue 인수 조건 충족
- Parent Epic 목표·범위 준수
- 유즈케이스와 도메인 규칙 준수
- OpenAPI·DB 계약과 구현 일치
- 범위 제외 기능을 임의로 추가하지 않았는지 확인

## 출력

```markdown
## Standards
- [심각도] `파일:줄` — 발견 내용과 영향

## Spec
- [심각도] `파일:줄` — 불일치와 근거가 되는 인수 조건

## Summary
- Standards: ...
- Spec: ...
```

축 사이의 발견을 합치거나 재순위화하지 않는다. 차단 문제가 없다면 각 축에서 명시적으로 통과했다고 적는다.
