# ADR-005: MVP는 단일 EC2와 Private RDS로 운영한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
- **개정일:** 2026-08-12
## 맥락
초기 출시에서는 운영 복잡도와 비용을 낮추는 것이 고가용성보다 중요하며, 단일 호스트 장애와 배포 중 일시 중단을 허용한다.
## 결정
- 서울 리전의 단일 AWS 계정과 단일 EC2를 사용한다.
- Cloudflare 프록시 → Nginx → Spring Boot 순서로 요청을 처리한다.
- Cloudflare는 `Full (strict)` TLS를 사용하고 EC2 443은 Cloudflare IP 대역에만 허용한다.
- 서버 관리는 SSH 대신 SSM을 사용한다.
- RDS PostgreSQL/PostGIS는 Private Subnet에 두고 EC2 Security Group에서만 접근한다.
- EC2는 stateless하며 영구 데이터는 RDS, S3와 CloudWatch에 저장한다.
- Multi-AZ와 다중 리전은 도입하지 않는다.
## 결과
- 구성이 단순하고 호스트를 ECR 이미지와 AWS Console 구성 Runbook으로 복구할 수 있다.
- EC2 장애와 배포 시 서비스 중단이 발생할 수 있다.
## 기각한 대안
- 초기부터 다중 EC2·로드 밸런서·Multi-AZ를 구성하는 방식
- RDS를 Public Access로 노출하는 방식
