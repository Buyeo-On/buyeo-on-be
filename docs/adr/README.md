# Architecture Decision Records

확정된 아키텍처 결정을 종류별 ADR로 관리한다.

- [ADR-001: 소셜 계정은 로그인된 회원이 명시적으로 연결한다](./adr-001-social-account-linking.md)
- [ADR-002: 장소 위치는 PostGIS geography로 저장한다](./adr-002-postgis-geography.md)
- [ADR-003: 배지는 메트릭 Provider와 데이터 조건으로 판정한다](./adr-003-badge-metric-provider.md)
- [ADR-004: 상태 컬럼은 명확한 생명주기가 있을 때만 둔다](./adr-004-explicit-lifecycle-status.md)
- [ADR-006: 배포와 인프라는 GitHub Actions·ECR·SSM·Terraform으로 관리한다](./adr-006-deployment-and-infrastructure.md)
- [ADR-005: MVP는 단일 EC2와 Private RDS로 운영한다](./adr-005-single-ec2-private-rds.md)
- [ADR-007: DB 변경과 운영 권한을 분리한다](./adr-007-database-permissions.md)
- [ADR-012: 서비스 세션은 단기 JWT와 회전형 Refresh Token으로 관리한다](./adr-012-jwt-refresh-token-session.md)
- [ADR-010: 백엔드는 모듈러 모놀리스와 인프로세스 작업으로 시작한다](./adr-010-modular-monolith.md)
- [ADR-009: 운영 관측성은 CloudWatch로 통합한다](./adr-009-cloudwatch-observability.md)
- [ADR-008: 미션 사진은 Private S3와 CloudFront Signed URL로 제공한다](./adr-008-private-mission-photos.md)
- [ADR-011: iOS 단일 출시를 Flutter 기능 중심 구조로 구현한다](./adr-011-flutter-ios-architecture.md)
