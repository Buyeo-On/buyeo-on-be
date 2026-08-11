# ADR-009: 운영 관측성은 CloudWatch로 통합한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
- **개정일:** 2026-08-12
## 맥락
stateless EC2가 손실돼도 로그를 보존하고 애플리케이션 오류와 AWS 리소스 장애를 빠르게 감지해야 한다. MVP에서는 지속적인 외부 가용성 Canary를 운영하지 않고 배포 시 공개 요청 경로를 검증한다.
## 결정
- 개발은 일반 로그, 운영은 SLF4J JSON 구조화 로그를 사용한다.
- Nginx `request_id`와 `CF-Ray`를 Spring MDC에 전달한다.
- Spring·Nginx 로그를 CloudWatch Logs에 30일 보관한다.
- 토큰, OAuth 코드, Presigned URL과 개인정보는 로그에서 제외한다.
- CloudWatch Alarm과 SNS 이메일로 EC2, 디스크, 5xx, RDS 저장 공간·연결 수를 감시한다.
- Docker HEALTHCHECK는 Actuator liveness를 확인한다.
- GitHub Actions는 배포 후 Cloudflare부터 Spring까지 공개 health endpoint를 확인한다.
## 결과
- 별도 ELK·APM 없이 로그와 지표를 CloudWatch에서 확인한다.
- 지속적인 외부 가용성 감지는 제공하지 않는다. 배포 사이에 발생한 DNS, Cloudflare, TLS 또는 Nginx 도달 불가 장애는 Nginx 5xx나 AWS 리소스 Metric을 만들지 않으면 다음 배포 검증까지 감지하지 못할 수 있다.
- CloudWatch 비용과 알람 임계값을 운영 중 조정해야 한다.
## 기각한 대안
- 로그를 EC2 로컬 파일에만 보관하는 방식
- MVP부터 ELK·OpenSearch·별도 APM을 운영하는 방식
- MVP부터 CloudWatch Synthetics Canary와 부속 IAM Role·Artifact Bucket을 운영하는 방식
