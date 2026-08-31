# ADR-013: 장소 데이터는 관리자 API로 TourAPI를 동기화한다

- **상태:** 승인됨
- **결정일:** 2026-08-18
## 맥락
`places`는 지금 예시 시드 한 건만 가지고 있다. Figma 장소 상세 화면(`explore_heritage_sheet`, `saved_detail_sheet`)은 이름·요약·설명·주소·대표이미지와 관람시간·입장료("핵심 정보")를 보여주므로, 사용자 요청 전에 한국관광공사 TourAPI 데이터를 내부 DB로 동기화해야 한다(`docs/architecture.md`). 부여 지역 하나만 대상이라 데이터 규모가 크지 않고, 회원 권한 체계(`ROLE_ADMIN` 등)는 아직 없다.
## 결정
- 관리자 전용 API 엔드포인트가 동기화를 트리거한다. Spring Scheduler나 앱 시작 시 자동 실행은 쓰지 않는다.
- 이 엔드포인트는 회원 인증과 별개로 Parameter Store `SecureString`에서 읽은 API Key 헤더로 보호한다.
- `areaBasedList2`로 부여 지역 contentId 목록을 수집하고, 각 항목마다 `detailCommon2`(이름·요약·설명·주소·대표이미지·이미지 이용허락 유형), `detailIntro2`(관람시간·입장료), `detailInfo2`(이용안내)를 호출한다.
- `places`는 `(source_name, external_id)` 기준으로 upsert한다. 이미 있는 행은 매번 모든 필드를 최신값으로 덮어쓴다.
- `detailIntro2`의 관람시간은 자유텍스트로 온다. 원문은 `operating_hours_raw`에 항상 저장하고, 파싱에 성공한 경우만 `opens_at`·`closes_at`·`admission_fee`를 채운다. 파싱 실패가 항목 처리 자체를 막지 않는다.
- 항목 하나에서 호출 실패나 파싱 오류가 나도 전체 동기화를 중단하지 않고 다음 항목으로 넘어간다. 응답에 실패한 contentId 목록을 포함한다.
- 응답은 동기식이다. 호출자는 전체 동기화가 끝날 때까지 기다려 성공·실패 개수를 받는다.
- 방문자 키워드 태그, `detailImage2`(추가 이미지), `searchFestival2`(축제)는 이번 동기화 범위에서 제외한다.
## 결과
- `places` 스키마에 `operating_hours_raw`, `opens_at`, `closes_at`, `admission_fee` 컬럼이 추가된다(`V10__add_place_operating_info.sql`).
- 자동 재시딩 주기는 정하지 않는다 — 필요할 때 운영자가 수동으로 호출한다(`docs/architecture.md` 미정·보류 참고).
- 회원 권한 체계와 무관하게 별도 API Key로 보호되므로, 인증 도메인(A)의 인가 범위 확장 없이 바로 구현할 수 있다.
- 부여 지역 규모(수백 건 이내)에서는 동기 호출로도 응답 시간이 감당 가능하다고 가정한다. 지역이 늘어나 호출량이 커지면 비동기 처리를 재검토해야 한다.
## 기각한 대안
- 서버 시작 시 자동 실행(`CommandLineRunner`) — 배포마다 반복 실행되는 게 통제하기 어려움
- 주기적 Spring Scheduler 자동 재시딩 — 지금 규모에서 자동화할 만한 갱신 압력이 없고, 운영 정책이 아직 안 잡혀 있음
- 회원 `ROLE_ADMIN` 신설 — 이 작업은 애초에 회원이 아니라 운영자·CI가 호출하는 성격이라 회원 권한 체계에 넣을 이유가 없음
- 부분 실패 시 전체 동기화 중단 — 공공데이터 특성상 항목 하나의 형식 오류로 전체가 자주 멈추는 걸 감수할 이유가 없음
- 비동기 처리(즉시 202 응답 후 별도 조회) — 부여 지역 규모에서는 오버엔지니어링, 진행상황 조회 엔드포인트까지 만들 필요 없음
- 재시딩 시 필드별 diff/변경분만 갱신 — TourAPI 응답이 원본이므로 항상 전체 덮어쓰기가 더 단순하고 예측 가능함
