# 도메인 모델과 규칙 인덱스

도메인 모델은 코드의 최상위 패키지와 같은 7개 context pack으로 관리한다.

- [도메인 지도와 컨텍스트 로딩 순서](./domains/README.md)
- [회원 규칙](./domains/member/rules.md)
- [여행 규칙](./domains/trip/rules.md)
- [장소 규칙](./domains/place/rules.md)
- [미션 규칙](./domains/mission/rules.md)
- [포인트 규칙](./domains/point/rules.md)
- [배지 규칙](./domains/badge/rules.md)
- [알림 규칙](./domains/notification/rules.md)
- [전역 정책](./policies/README.md)

에이전트는 전체 도메인 문서를 기본으로 읽지 않는다. 먼저 대상 [유즈케이스](./use-cases.md)를 찾고 해당 파일의 frontmatter가 지정한 owner, participants, policies와 ADR만 추가로 읽는다.
