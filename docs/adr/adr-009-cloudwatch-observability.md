# ADR-009: 운영 관측성은 CloudWatch로 통합한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
## 맥락
stateless EC2가 손실돼도 로그를 보존하고 외부 요청 경로와 AWS 리소스 장애를 빠르게 감지해야 한다.
## 결정
- 개발은 일반 로그, 운영은 SLF4J JSON 구조화 로그를 사용한다.
- Nginx `request_id`와 `CF-Ray`를 Spring MDC에 전달한다.
- Spring·Nginx 로그를 CloudWatch Logs에 30일 보관한다.
- 토큰, OAuth 코드, Signed URL과 개인정보는 로그에서 제외한다.
- CloudWatch Alarm과 SNS 이메일로 EC2, 디스크, 5xx, RDS 저장 공간·연결 수를 감시한다.
- Docker HEALTHCHECK는 Actuator liveness를 확인한다.
- CloudWatch Synthetics는 5분마다 Cloudflare부터 Spring까지 공개 헬스 경로를 확인한다.
## 결과
- 별도 ELK·APM 없이 로그, 지표와 외부 가용성을 한곳에서 확인한다.
- CloudWatch 비용과 알람 임계값을 운영 중 조정해야 한다.
## 기각한 대안
- 로그를 EC2 로컬 파일에만 보관하는 방식
- MVP부터 ELK·OpenSearch·별도 APM을 운영하는 방식
