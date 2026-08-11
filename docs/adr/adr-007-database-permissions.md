# ADR-007: MVP는 애플리케이션 시작 시 Flyway를 실행한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
- **개정일:** 2026-08-11
## 맥락
운영 스키마를 JPA가 임의 변경하지 않아야 한다. 한편 단일 EC2와 작은 팀으로 운영하는 MVP에서 별도 migration 이미지, 실행 단계와 PostgreSQL Role을 관리하는 비용은 현재 위험에 비해 크다.
## 결정
- 운영 JPA는 `ddl-auto=validate`를 사용한다.
- Spring Boot가 시작될 때 Flyway 마이그레이션을 실행하고, 실패하면 애플리케이션 시작도 실패한다.
- MVP에서는 Flyway DDL과 애플리케이션 DML에 하나의 PostgreSQL Role을 사용한다.
- RDS 관리자 계정은 PostGIS 설치와 최초 bootstrap에만 사용하고 애플리케이션에 제공하지 않는다.
- 파괴적 변경은 호환 가능한 단계로 나누고 자동 down migration을 사용하지 않는다.
- 공개 이미지 `image_url`을 `image_key`로 바꾸는 V5는 운영 시작 전에만 적용하는 pre-production boundary로 두고, V5 이전 앱으로의 rollback은 지원하지 않는다. 운영 시작 후 같은 종류의 변경은 expand/contract 단계로 나눈다.
- RDS 자동 백업은 7일 보존하며 PITR과 삭제 방지를 활성화한다. RDS 삭제 절차에서는 이름이 지정된 최종 Snapshot 생성과 완료를 확인한다.
- 애플리케이션은 `Instant`, DB는 `timestamptz`, 시스템 시간대는 UTC를 사용하고 표시·날짜 판정만 `Asia/Seoul`을 사용한다.
## 결과
- 별도 migration 이미지와 자격증명 없이 하나의 이미지와 DB Role로 배포한다.
- 마이그레이션 실패 시 새 버전은 시작되지 않는다.
- 애플리케이션 침해 시 DDL 권한도 노출될 수 있는 위험을 MVP 동안 수용한다.
- 규모와 운영 인력이 늘면 migration 실행과 PostgreSQL Role 분리를 재검토한다.
## 기각한 대안
- 운영에서 Hibernate가 스키마를 자동 변경하는 방식
- MVP부터 별도 migration 이미지와 DDL 전용 PostgreSQL Role을 운영하는 방식
