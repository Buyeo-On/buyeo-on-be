# ADR-009: 운영 관측성은 CloudWatch로 통합한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
- **개정일:** 2026-08-13
## 맥락
stateless EC2가 손실돼도 로그를 보존해야 한다. MVP에서는 별도의 metric·Alarm·알림과 지속적인 외부 가용성 Canary를 운영하지 않고 배포 시 공개 요청 경로를 검증한다.
## 결정
- 개발은 일반 로그, 운영은 SLF4J JSON 구조화 로그를 사용한다.
- Nginx `request_id`와 `CF-Ray`를 Spring MDC에 전달한다.
- Spring·Nginx 로그를 CloudWatch Logs에 30일 보관한다.
- 토큰, OAuth 코드, Presigned URL과 개인정보는 로그에서 제외한다.
- Docker HEALTHCHECK는 Actuator liveness를 확인한다.
- GitHub Actions는 배포 후 Cloudflare부터 Spring까지 공개 health endpoint를 확인한다.
## 결과
- 별도 ELK·APM 없이 로그를 CloudWatch에서 확인한다.
- custom metric, CloudWatch Alarm, SNS Topic·Subscription과 이메일 알림은 구성하지 않는다.
- 지속적인 장애 감지는 제공하지 않는다. 배포 사이에 발생한 애플리케이션 오류나 AWS 리소스 장애는 로그를 직접 확인하거나 다음 배포를 검증하기 전까지 감지하지 못할 수 있다.
- CloudWatch Logs 비용과 보관 기간을 운영 중 조정해야 한다.
## 기각한 대안
- 로그를 EC2 로컬 파일에만 보관하는 방식
- MVP부터 ELK·OpenSearch·별도 APM을 운영하는 방식
- MVP부터 CloudWatch Synthetics Canary와 부속 IAM Role·Artifact Bucket을 운영하는 방식
