---
name: to-spec
description: 사용자가 명시적으로 요청했을 때 완료된 유즈케이스 그릴링 대화를 구현 가능한 GitHub Epic Issue 명세로 합성하고 발행한다. 추가 인터뷰 없이 grill-with-docs 결과를 Epic으로 만들 때 사용한다.
---

# To spec

현재 대화와 프로젝트 문서를 합성해 GitHub Epic Issue를 만든다. 새로운 그릴링을 시작하지 않는다. 정보가 부족해 인수 조건을 쓸 수 없으면 Issue를 만들지 말고 부족한 결정만 알린다.

## 준비

1. `AGENTS.md`와 대상 유즈케이스 파일을 읽고 frontmatter의 owner, participants, policies, api와 adrs가 가리키는 context pack만 다시 확인한다.
2. 문서 변경이 합의됐다면 Epic보다 먼저 반영됐는지 확인한다.
3. `gh auth status`와 `gh repo view`로 GitHub 대상과 인증 상태를 확인한다. 비밀 값은 출력하지 않는다.
4. 기존 Epic을 검색해 중복 생성을 피한다.
5. 테스트할 가장 높은 공개 seam을 정한다. 기본은 HTTP API와 실제 DB를 통과하는 통합 테스트이며, 더 좁은 seam이 필요한 이유가 있어야 한다.

## Epic 본문

[Epic Issue 템플릿](../../../.github/ISSUE_TEMPLATE/epic.md)을 읽고 모든 섹션을 채운다. 이 스킬 안에 별도의 본문 형식을 만들지 않고 해당 파일을 단일 원본으로 사용한다.

문서 전체를 복사하지 말고 링크와 이번 Epic의 범위 차이만 적는다. 인수 조건은 내부 클래스나 메서드가 아니라 API 응답, 상태 변화, 데이터 정합성처럼 검증 가능한 동작으로 작성한다.

초안을 사용자에게 보여주고 발행 승인을 받은 후 `gh issue create`로 생성한다. 저장소에 `epic` 또는 준비 상태 라벨이 이미 있으면 적용하고, 없으면 임의로 만들지 않는다. 생성 후 Issue 번호와 URL을 반환한다.
