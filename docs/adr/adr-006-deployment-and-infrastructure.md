# ADR-006: 배포와 인프라는 GitHub Actions·ECR·SSM·Terraform으로 관리한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
## 맥락
단일 EC2 배포도 재현 가능하고 롤백 가능해야 하며 팀원이 공유하는 AWS 상태를 수동 콘솔 작업에 의존하지 않아야 한다.
## 결정
- GitHub Actions가 테스트 후 커밋 SHA 태그의 이미지를 ECR에 저장한다.
- SSM으로 EC2가 이미지를 pull하고 Docker Compose를 실행한다.
- Actuator 헬스 체크 실패 시 이전 커밋 SHA 이미지로 자동 롤백한다.
- Terraform으로 AWS 인프라를 관리한다.
- Remote State는 암호화·버전 관리·잠금이 적용된 S3에서 공유한다.
- 운영 apply와 배포는 GitHub Actions만 수행하고 OIDC IAM Role을 사용한다.
- Terraform Role과 배포 Role을 분리하며 장기 AWS Access Key를 사용하지 않는다.
- AWS에는 운영만 상시 유지하고 개발은 로컬·CI의 임시 환경을 사용한다.
## 결과
- 동일한 이미지를 배포·롤백하고 인프라 변경을 PR에서 검토할 수 있다.
- Terraform state와 GitHub Environment 권한 관리가 필요하다.
## 기각한 대안
- EC2에서 소스를 받아 직접 빌드하는 방식
- 콘솔에서 운영 인프라를 수동 관리하는 방식
- GitHub에 장기 AWS Access Key를 저장하는 방식
