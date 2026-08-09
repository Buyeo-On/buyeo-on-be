---
name: to-tickets
description: 사용자가 명시적으로 요청했을 때 GitHub Epic Issue를 한 컨텍스트에서 구현 가능한 수직 슬라이스 Sub-issue들로 나누고 선행 의존성을 연결한다. 부여ON 유즈케이스 Epic의 구현 티켓을 만들 때 사용한다.
---

# To tickets

지정된 Epic Issue와 연결된 프로젝트 문서를 읽고 구현 Sub-issue를 설계한다.

## 티켓 분할

- 기술 계층별 티켓(`DB`, `Repository`, `Service`, `Controller`)으로 나누지 않는다.
- 각 티켓은 가능한 한 스키마부터 API와 테스트까지 통과하는 좁고 완결된 수직 슬라이스여야 한다.
- 완료된 티켓은 독립적으로 시연하거나 검증할 수 있어야 한다.
- 한 티켓은 새 컨텍스트 하나에서 구현·리뷰할 수 있는 크기로 제한한다.
- 변경을 쉽게 만드는 선행 정리가 필요하면 별도 prefactor 티켓으로 둔다.
- 넓은 전환은 수직 슬라이스를 강제하지 않고 expand → migrate → contract 순서로 나눈다.

## 티켓 본문

[Sub-issue 템플릿](../../../.github/ISSUE_TEMPLATE/ticket.md)을 읽고 모든 섹션을 채운다. 이 스킬 안에 별도의 본문 형식을 만들지 않고 해당 파일을 단일 원본으로 사용한다.

## 발행

1. 티켓 제목, 범위, 인수 조건과 blocking graph를 먼저 제안한다.
2. 사용자와 크기·의존성을 확인한 후 dependency frontier 순서로 발행한다.
3. 지원되는 GitHub CLI에서는 다음처럼 부모와 의존성을 연결한다.

```bash
gh issue create --title "..." --body-file "..." --parent <epic-number>
gh issue create --title "..." --body-file "..." --parent <epic-number> --blocked-by <issue-number>
```

4. CLI가 해당 옵션을 지원하지 않으면 Issue를 만든 뒤 부모 관계와 blocking 관계를 각각 API로 연결한다. API 본문의 값은 표시용 Issue 번호가 아니라 숫자형 database ID다.

```bash
CHILD_ID=$(gh api repos/{owner}/{repo}/issues/<child-number> --jq '.id')
gh api repos/{owner}/{repo}/issues/<epic-number>/sub_issues \
  -X POST -F sub_issue_id="$CHILD_ID"

BLOCKER_ID=$(gh api repos/{owner}/{repo}/issues/<blocker-number> --jq '.id')
gh api repos/{owner}/{repo}/issues/<child-number>/dependencies/blocked_by \
  -X POST -F issue_id="$BLOCKER_ID"
```

5. 저장소에 `ticket` 또는 준비 상태 라벨이 이미 있으면 적용하고, 없으면 임의로 만들지 않는다.
6. Epic은 닫거나 본문의 기존 명세를 바꾸지 않는다. 생성한 Sub-issue 목록만 Epic에 보이도록 연결한다.

마지막에 현재 바로 구현 가능한 frontier와 blocker가 남은 티켓을 구분해 보고한다.
