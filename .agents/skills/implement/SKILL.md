---
name: implement
description: 사용자가 명시적으로 요청했을 때 GitHub Sub-issue와 Parent Epic을 기준으로 부여ON 백엔드 수직 슬라이스를 TDD로 구현하고 코드 리뷰까지 완료한다. 준비된 구현 티켓을 수행할 때 사용한다.
---
# Implement

하나의 준비된 Sub-issue를 구현한다. Epic 전체를 한 번에 구현하거나 티켓 범위를 임의로 확장하지 않는다.

## 준비

1. Sub-issue 본문, Parent Epic, blocker 상태를 확인한다.
2. `AGENTS.md`와 연결된 프로젝트 문서를 읽는다.
3. blocker가 끝나지 않았거나 명세와 문서가 충돌하면 구현을 멈추고 차이를 보고한다.
4. 현재 브랜치와 작업 트리가 의도한 작업용인지 확인한다. `main`에서 직접 구현하지 않는다.
5. 티켓의 테스트 seam과 인수 조건을 구현 계획으로 정리한다.

## 구현

1. `tdd` 스킬을 사용해 인수 조건을 한 개씩 red → green으로 구현한다.
2. 기존 패키지 경계와 공개 인터페이스를 우선 재사용한다.
3. 관련 테스트를 자주 실행한다.
4. OpenAPI나 DB 계약을 변경했다면 원본 문서와 파생 문서를 함께 맞춘다.
5. 구현이 끝나면 `scripts/test`의 검증 스크립트를 순서대로 실행한다.
   - `./scripts/test/format.sh`
   - `./scripts/test/static-check.sh`
   - `./scripts/test/test.sh`
   - `./scripts/test/docker-build.sh`

## 리뷰와 종료

1. `code-review` 스킬로 Standards와 Spec 리뷰를 수행한다.
2. 발견된 차단 문제를 수정하고 관련 테스트 및 전체 테스트를 다시 실행한다.
3. 변경 파일, 충족한 인수 조건, 테스트 결과, 남은 위험을 요약한다.
4. 사용자가 명시적으로 요청한 경우에만 커밋한다. 푸시, PR 생성, Issue 종료와 merge는 별도 요청 없이 수행하지 않는다.
