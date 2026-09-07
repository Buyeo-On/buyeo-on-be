# ADR-009: 운영 관측성은 CloudWatch로 통합한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
- **개정일:** 2026-09-07
## 맥락
stateless EC2가 손실돼도 로그를 보존해야 한다. MVP에서는 별도의 metric·Alarm·알림과 지속적인 외부 가용성 Canary를 운영하지 않고 배포 시 공개 요청 경로를 검증한다.

2026-09-07 추가: 운영 Spring 로그에 기동·종료 외의 기록이 없었다. Spring은 정상 요청을 스스로 기록하지 않고 애플리케이션 로그 호출도 없어, 요청 안에서 무슨 일이 있었는지 추적할 수 없었다.
## 결정
- 개발은 일반 로그, 운영은 SLF4J JSON 구조화 로그를 사용한다.
- Nginx `request_id`와 `CF-Ray`를 Spring MDC에 전달한다.
- Spring·Nginx 로그를 CloudWatch Logs에 30일 보관한다.
- 토큰, OAuth 코드, Presigned URL과 개인정보는 로그에서 제외한다.
- 요청마다 메서드·경로·상태·처리 시간을 INFO 한 줄로 남긴다(4xx WARN, 5xx ERROR). 쿼리스트링과 본문은 남기지 않고, Actuator는 제외한다.
- 회원 ID는 요청 로그에 남기지 않는다. 사용자 문의 추적에는 유용하지만, 팀 논의 결과 개인정보 처리방침에 로그 보관 항목을 추가하지 않기로 했고 정책 근거 없이 식별자를 보관하지 않는다. 문의는 request_id와 시각으로 추적한다.
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
- `logging.level.org.springframework.web=DEBUG`로 요청 로그를 얻는 방식: 프레임워크 내부 동작이 요청마다 수십 줄 쏟아져 읽을 수 없고 보관 비용만 는다.
- `CommonsRequestLoggingFilter`: 요청 본문을 그대로 남겨 위 개인정보 제외 원칙을 어기고, 평문 한 덩어리라 구조화 로그와 맞지 않으며 DEBUG 레벨을 켜야 보인다.
